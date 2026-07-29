package com.plm.tools.partloader.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.plm.tools.partloader.model.LoadSummary;
import com.plm.tools.partloader.model.RowResult;

/**
 * Writes the per-row execution report.
 *
 * <p>This file is the audit artefact. In a regulated or IATF-audited environment the
 * question asked six months later is never "did the loader work" but "prove which objects
 * this run created, from which source line, on whose authority". One CSV per run, named
 * with a timestamp and never overwritten, answers that question cheaply.</p>
 */
public final class ReportWriter {

    private static final SimpleDateFormat FILE_TS = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private ReportWriter() {
    }

    /**
     * @return absolute path of the written report
     */
    public static String write(String directory, String runId, String user, LoadSummary summary) throws IOException {
        File dir = new File(directory);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create report directory: " + dir.getAbsolutePath());
        }
        File out = new File(dir, "partloader-" + FILE_TS.format(new Date()) + "-" + runId + ".csv");

        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"));
        try {
            w.write("# Windchill Part Loader report\n");
            w.write("# run id   : " + runId + "\n");
            w.write("# executed : " + new Date() + "\n");
            w.write("# user     : " + user + "\n");
            w.write("# mode     : " + (summary.isDryRun() ? "DRY-RUN" : "COMMIT") + "\n");
            w.write("section,line,key,status,message\n");
            writeSection(w, "PART", summary.getPartResults());
            writeSection(w, "BOM", summary.getBomResults());
        } finally {
            w.close();
        }
        return out.getAbsolutePath();
    }

    private static void writeSection(Writer w, String section, List<RowResult> results) throws IOException {
        for (RowResult r : results) {
            w.write(section);
            w.write(',');
            w.write(String.valueOf(r.getLineNumber()));
            w.write(',');
            w.write(quote(r.getKey()));
            w.write(',');
            w.write(r.getStatus().name());
            w.write(',');
            w.write(quote(r.getMessage()));
            w.write('\n');
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\r", " ").replace("\n", " ");
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
