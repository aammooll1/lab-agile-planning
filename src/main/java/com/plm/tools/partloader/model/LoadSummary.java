package com.plm.tools.partloader.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated run result, returned from the method server to the calling client.
 *
 * <p>Must stay {@link Serializable} with only JDK types on the wire: it crosses the RMI
 * boundary of {@code wt.method.RemoteMethodServer}, and the client JVM started by the
 * {@code windchill} shell script does not necessarily share every Windchill class the
 * server used while building the result.</p>
 */
public class LoadSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    // Declared as ArrayList rather than List on purpose: this object is serialized across
    // the RMI boundary, and the concrete type is what has to be Serializable.
    private final ArrayList<RowResult> partResults = new ArrayList<RowResult>();
    private final ArrayList<RowResult> bomResults = new ArrayList<RowResult>();

    private boolean dryRun;
    private boolean aborted;
    private String abortReason;
    private long elapsedMillis;
    private String reportPath;

    // ---------------------------------------------------------------- mutation

    public void addPartResult(RowResult result) {
        partResults.add(result);
    }

    public void addBomResult(RowResult result) {
        bomResults.add(result);
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public void abort(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    // ---------------------------------------------------------------- query

    public List<RowResult> getPartResults() {
        return partResults;
    }

    public List<RowResult> getBomResults() {
        return bomResults;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isAborted() {
        return aborted;
    }

    public String getAbortReason() {
        return abortReason;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getReportPath() {
        return reportPath;
    }

    public int getFailureCount() {
        return count(partResults, RowResult.Status.FAILED) + count(bomResults, RowResult.Status.FAILED);
    }

    /** Process exit code: 0 = clean, 1 = at least one row failed, 2 = run aborted. */
    public int getExitCode() {
        if (aborted) {
            return 2;
        }
        return getFailureCount() > 0 ? 1 : 0;
    }

    private static int count(List<RowResult> results, RowResult.Status status) {
        int n = 0;
        for (RowResult r : results) {
            if (r.getStatus() == status) {
                n++;
            }
        }
        return n;
    }

    private static Map<RowResult.Status, Integer> tally(List<RowResult> results) {
        Map<RowResult.Status, Integer> map = new EnumMap<RowResult.Status, Integer>(RowResult.Status.class);
        for (RowResult.Status s : RowResult.Status.values()) {
            map.put(s, Integer.valueOf(0));
        }
        for (RowResult r : results) {
            map.put(r.getStatus(), Integer.valueOf(map.get(r.getStatus()).intValue() + 1));
        }
        return map;
    }

    /** Operator-facing summary block, printed at the end of every run. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("================ PART LOADER SUMMARY ================\n");
        sb.append("Mode              : ").append(dryRun ? "DRY-RUN (nothing persisted)" : "COMMIT").append('\n');
        sb.append("Elapsed           : ").append(elapsedMillis / 1000L).append(" s\n");
        appendSection(sb, "Parts", partResults);
        appendSection(sb, "BOM links", bomResults);
        if (reportPath != null) {
            sb.append("Detailed report   : ").append(reportPath).append('\n');
        }
        if (aborted) {
            sb.append("RUN ABORTED       : ").append(abortReason).append('\n');
        }
        sb.append("=====================================================");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String label, List<RowResult> results) {
        if (results.isEmpty()) {
            return;
        }
        Map<RowResult.Status, Integer> t = tally(results);
        sb.append(label).append(" processed : ").append(results.size()).append('\n');
        for (RowResult.Status s : RowResult.Status.values()) {
            int n = t.get(s).intValue();
            if (n > 0) {
                sb.append("  ").append(pad(s.name())).append(": ").append(n).append('\n');
            }
        }
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 14) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
