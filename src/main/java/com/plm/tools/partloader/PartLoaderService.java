package com.plm.tools.partloader;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.plm.tools.partloader.io.CsvReader;
import com.plm.tools.partloader.io.ReportWriter;
import com.plm.tools.partloader.io.RowMapper;
import com.plm.tools.partloader.model.BomRow;
import com.plm.tools.partloader.model.LoadSummary;
import com.plm.tools.partloader.model.PartRow;
import com.plm.tools.partloader.model.RowResult;
import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;
import com.plm.tools.partloader.wc.BomBuilder;
import com.plm.tools.partloader.wc.ContextResolver;
import com.plm.tools.partloader.wc.PartFactory;

import wt.method.RemoteAccess;
import wt.pom.Transaction;
import wt.session.SessionHelper;
import wt.util.WTException;

/**
 * Server-side entry point. Everything below this class runs inside the method server.
 *
 * <p><b>Why it runs server-side rather than in the client JVM.</b> A loader that calls
 * Windchill APIs from a remote client executes every single call as a separate RMI round
 * trip. For 40 000 parts with a dozen calls each that is half a million network round trips,
 * and the run takes hours instead of minutes. Implementing {@link RemoteAccess} and letting
 * the client invoke one method through {@code wt.method.RemoteMethodServer} moves the whole
 * loop inside the server; only the arguments and the summary cross the wire.</p>
 *
 * <p><b>Consequence you must plan for:</b> the CSV paths are resolved on the <em>server</em>
 * host, not the operator's workstation. Put the load files somewhere the method server user
 * can read, conventionally {@code $WT_HOME/loadFiles/}.</p>
 *
 * <p>For a remote invocation to be permitted the class must implement {@link RemoteAccess}
 * and the invoked method must be {@code public static}. Both are required; missing either
 * produces {@code wt.method.MethodAccessException} at runtime rather than a compile error.</p>
 */
public class PartLoaderService implements RemoteAccess, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Runs a complete load.
     *
     * @param configPath    server-side path to the loader properties file
     * @param partsCsvPath  server-side path to the parts file, or null to skip the parts phase
     * @param bomCsvPath    server-side path to the BOM file, or null to skip the structure phase
     * @param dryRun        when true nothing is persisted; every row is validated and reported
     */
    public static LoadSummary execute(String configPath, String partsCsvPath, String bomCsvPath, boolean dryRun)
            throws WTException {

        long started = System.currentTimeMillis();
        String runId = Long.toHexString(started);
        LoadSummary summary = new LoadSummary();
        summary.setDryRun(dryRun);

        String user;
        try {
            user = SessionHelper.manager.getPrincipal().getName();
        } catch (Exception e) {
            user = "unknown";
        }

        LoaderLog.info("=== Part Loader run " + runId + " started by " + user
                + (dryRun ? " in DRY-RUN mode ===" : " in COMMIT mode ==="));

        try {
            LoaderConfig config = LoaderConfig.load(configPath);
            LoaderLog.setDebugEnabled(config.getBoolean("loader.debug", false));

            ContextResolver context = new ContextResolver(config.isAutoCreateFolders());
            RowMapper mapper = new RowMapper(config);

            if (partsCsvPath != null && partsCsvPath.trim().length() > 0) {
                loadParts(config, mapper, context, new File(partsCsvPath), dryRun, summary);
            }
            if (!summary.isAborted() && bomCsvPath != null && bomCsvPath.trim().length() > 0) {
                loadBom(config, mapper, context, new File(bomCsvPath), dryRun, summary);
            }

            summary.setElapsedMillis(System.currentTimeMillis() - started);
            try {
                summary.setReportPath(ReportWriter.write(config.getReportDirectory(), runId, user, summary));
            } catch (Exception e) {
                LoaderLog.error("Report could not be written; results are still in this log", e);
            }

        } catch (Exception e) {
            LoaderLog.error("Part Loader run " + runId + " failed", e);
            summary.abort(LoaderLog.describe(e));
            summary.setElapsedMillis(System.currentTimeMillis() - started);
        }

        LoaderLog.info(summary.format());
        return summary;
    }

    // ---------------------------------------------------------------- phase 1: parts

    private static void loadParts(LoaderConfig config, RowMapper mapper, ContextResolver context,
                                  File file, boolean dryRun, LoadSummary summary) throws Exception {

        LoaderLog.info("Parts phase: reading " + file.getAbsolutePath());
        PartFactory factory = new PartFactory(config, context);

        CsvReader reader = new CsvReader(file, config.getCharset(), config.getDelimiter());
        try {
            reader.readHeaders();
            Map<String, String> record;
            int processed = 0;
            int failures = 0;

            while ((record = reader.readRecord()) != null) {
                long line = reader.getLineNumber();
                RowResult result;
                try {
                    PartRow row = mapper.toPartRow(record, line);
                    result = runInTransaction(factory, row, dryRun);
                } catch (LoaderException e) {
                    result = new RowResult(line, String.valueOf(record.get(RowMapper.COL_NUMBER)),
                            RowResult.Status.FAILED, e.getMessage());
                }
                summary.addPartResult(result);
                processed++;

                if (result.isFailure()) {
                    failures++;
                    LoaderLog.warn("Row " + line + " FAILED: " + result.getMessage());
                    if (config.isStopOnError()) {
                        summary.abort("loader.stopOnError is true and row " + line + " failed");
                        return;
                    }
                    if (config.getMaxErrors() >= 0 && failures > config.getMaxErrors()) {
                        summary.abort("Error threshold exceeded (" + failures + " > loader.maxErrors="
                                + config.getMaxErrors() + ")");
                        return;
                    }
                }
                if (config.getProgressInterval() > 0 && processed % config.getProgressInterval() == 0) {
                    LoaderLog.info("  ... " + processed + " part rows processed, " + failures + " failed");
                }
            }
            LoaderLog.info("Parts phase complete: " + processed + " rows, " + failures + " failed");
        } finally {
            reader.close();
        }
    }

    /**
     * One transaction per row.
     *
     * <p>The alternative — one transaction for the whole file — is faster and almost always
     * wrong for a loader. It means row 39 999 failing discards the 39 998 good rows before it,
     * and it holds database locks across the entire run, which will block interactive users
     * and can exhaust rollback segments. Per-row commit costs some throughput and buys
     * restartability: fix the failed rows, re-run the file, and the loader skips what already
     * exists.</p>
     *
     * <p>Note that a failed row leaves nothing behind precisely because its own transaction is
     * rolled back — this is what makes the SKIPPED/CREATED counts in the report trustworthy.</p>
     */
    private static RowResult runInTransaction(PartFactory factory, PartRow row, boolean dryRun) {
        if (dryRun) {
            // Read-only path: no transaction needed, and opening one would only invite an
            // accidental write to be committed.
            return factory.process(row, true);
        }
        Transaction tx = new Transaction();
        try {
            tx.start();
            RowResult result = factory.process(row, false);
            if (result.isFailure()) {
                tx.rollback();
                return result;
            }
            tx.commit();
            tx = null;
            return result;
        } catch (Exception e) {
            return new RowResult(row.getLineNumber(), row.getNumber(), RowResult.Status.FAILED,
                    LoaderLog.describe(e));
        } finally {
            if (tx != null) {
                try {
                    tx.rollback();
                } catch (Exception e) {
                    LoaderLog.error("Rollback failed on line " + row.getLineNumber(), e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- phase 2: structure

    private static void loadBom(LoaderConfig config, RowMapper mapper, ContextResolver context,
                                File file, boolean dryRun, LoadSummary summary) throws Exception {

        LoaderLog.info("Structure phase: reading " + file.getAbsolutePath());
        BomBuilder builder = new BomBuilder(config, context);

        // Group by parent+view, preserving file order within each group. See BomBuilder for
        // why one checkout per parent rather than one per line matters.
        Map<String, List<BomRow>> byParent = new LinkedHashMap<String, List<BomRow>>();

        CsvReader reader = new CsvReader(file, config.getCharset(), config.getDelimiter());
        try {
            reader.readHeaders();
            Map<String, String> record;
            while ((record = reader.readRecord()) != null) {
                long line = reader.getLineNumber();
                try {
                    BomRow row = mapper.toBomRow(record, line);
                    String key = row.getParentNumber() + "|" + row.getParentView();
                    List<BomRow> group = byParent.get(key);
                    if (group == null) {
                        group = new ArrayList<BomRow>();
                        byParent.put(key, group);
                    }
                    group.add(row);
                } catch (LoaderException e) {
                    summary.addBomResult(new RowResult(line, "", RowResult.Status.FAILED, e.getMessage()));
                }
            }
        } finally {
            reader.close();
        }

        LoaderLog.info("Structure phase: " + byParent.size() + " parent assemblies to process");
        int processed = 0;

        for (Map.Entry<String, List<BomRow>> entry : byParent.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String parentNumber = parts[0];
            String viewName = parts.length > 1 ? parts[1] : config.getDefaultView();

            List<RowResult> results = runBomInTransaction(builder, parentNumber, viewName, entry.getValue(), dryRun);
            boolean anyFailure = false;
            for (RowResult r : results) {
                summary.addBomResult(r);
                anyFailure |= r.isFailure();
            }
            processed++;

            if (anyFailure && config.isStopOnError()) {
                summary.abort("loader.stopOnError is true and parent " + parentNumber + " failed");
                return;
            }
            if (config.getProgressInterval() > 0 && processed % config.getProgressInterval() == 0) {
                LoaderLog.info("  ... " + processed + " assemblies processed");
            }
        }
        LoaderLog.info("Structure phase complete: " + processed + " assemblies");
    }

    /** One transaction per parent assembly — the structure of an assembly is all or nothing. */
    private static List<RowResult> runBomInTransaction(BomBuilder builder, String parentNumber, String viewName,
                                                       List<BomRow> rows, boolean dryRun) {
        if (dryRun) {
            return builder.processParent(parentNumber, viewName, rows, true);
        }
        Transaction tx = new Transaction();
        try {
            tx.start();
            List<RowResult> results = builder.processParent(parentNumber, viewName, rows, false);
            boolean anyFailure = false;
            for (RowResult r : results) {
                anyFailure |= r.isFailure();
            }
            if (anyFailure) {
                tx.rollback();
            } else {
                tx.commit();
            }
            tx = null;
            return results;
        } catch (Exception e) {
            List<RowResult> results = new ArrayList<RowResult>();
            for (BomRow row : rows) {
                results.add(new RowResult(row.getLineNumber(),
                        row.getParentNumber() + " -> " + row.getChildNumber(),
                        RowResult.Status.FAILED, LoaderLog.describe(e)));
            }
            return results;
        } finally {
            if (tx != null) {
                try {
                    tx.rollback();
                } catch (Exception e) {
                    LoaderLog.error("Rollback failed for parent " + parentNumber, e);
                }
            }
        }
    }
}
