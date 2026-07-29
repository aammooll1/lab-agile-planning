package com.plm.tools.partloader;

import java.io.Console;

import com.plm.tools.partloader.model.LoadSummary;
import com.plm.tools.partloader.util.LoaderLog;

import wt.method.RemoteMethodServer;

/**
 * Command-line client. Runs in the JVM started by the {@code windchill} shell script and
 * does exactly one thing: authenticate, invoke {@link PartLoaderService#execute} inside the
 * method server, print the summary, exit with a meaningful code.
 *
 * <pre>
 * windchill com.plm.tools.partloader.PartLoaderMain \
 *     --config  /opt/ptc/Windchill/loadFiles/partloader.properties \
 *     --parts   /opt/ptc/Windchill/loadFiles/parts.csv \
 *     --bom     /opt/ptc/Windchill/loadFiles/bom.csv \
 *     --user    wcadmin \
 *     --dry-run
 * </pre>
 *
 * <p>Exit codes exist so this can be driven from a scheduler or a migration runbook without
 * a human reading the log: 0 clean, 1 completed with row failures, 2 aborted, 3 bad usage.</p>
 */
public final class PartLoaderMain {

    private PartLoaderMain() {
    }

    public static void main(String[] args) {
        String configPath = null;
        String partsPath = null;
        String bomPath = null;
        String user = null;
        String password = System.getenv("WC_PASSWORD");
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) && i + 1 < args.length) {
                configPath = args[++i];
            } else if ("--parts".equals(arg) && i + 1 < args.length) {
                partsPath = args[++i];
            } else if ("--bom".equals(arg) && i + 1 < args.length) {
                bomPath = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--debug".equals(arg)) {
                LoaderLog.setDebugEnabled(true);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                usage();
                System.exit(0);
            } else {
                System.err.println("Unrecognised argument: " + arg);
                usage();
                System.exit(3);
            }
        }

        if (configPath == null || (partsPath == null && bomPath == null)) {
            System.err.println("A --config and at least one of --parts / --bom are required.");
            usage();
            System.exit(3);
        }
        if (user == null) {
            System.err.println("--user is required.");
            System.exit(3);
        }

        // Passing a password on the command line puts it in the shell history and in the
        // process table, where any other user on the server can read it with ps. Prompt
        // instead, and accept WC_PASSWORD only for unattended scheduler runs.
        if (password == null || password.length() == 0) {
            Console console = System.console();
            if (console == null) {
                System.err.println("No console available for the password prompt. "
                        + "Set the WC_PASSWORD environment variable for unattended runs.");
                System.exit(3);
            }
            char[] entered = console.readPassword("Password for %s: ", user);
            password = entered == null ? "" : new String(entered);
        }

        int exitCode;
        try {
            RemoteMethodServer server = RemoteMethodServer.getDefault();
            server.setUserName(user);
            server.setPassword(password);

            LoaderLog.info("Invoking part loader on the method server as " + user
                    + (dryRun ? " (DRY-RUN)" : " (COMMIT)"));

            // Argument types must be declared exactly as the target method signature: the
            // boolean is primitive, so Boolean.TYPE and not Boolean.class. Getting this
            // wrong throws wt.method.MethodAccessException "no such method" at runtime.
            LoadSummary summary = (LoadSummary) server.invoke(
                    "execute",
                    PartLoaderService.class.getName(),
                    null,
                    new Class[] { String.class, String.class, String.class, Boolean.TYPE },
                    new Object[] { configPath, partsPath, bomPath, Boolean.valueOf(dryRun) });

            System.out.println();
            System.out.println(summary.format());
            exitCode = summary.getExitCode();

        } catch (Throwable t) {
            LoaderLog.error("Part loader could not be executed", t);
            exitCode = 2;
        }
        System.exit(exitCode);
    }

    private static void usage() {
        System.out.println();
        System.out.println("Usage: windchill com.plm.tools.partloader.PartLoaderMain [options]");
        System.out.println();
        System.out.println("  --config <path>    loader properties file            (required)");
        System.out.println("  --parts  <path>    parts CSV                         (parts phase)");
        System.out.println("  --bom    <path>    BOM CSV                           (structure phase)");
        System.out.println("  --user   <name>    Windchill user                    (required)");
        System.out.println("  --password <pw>    password; prefer WC_PASSWORD or the prompt");
        System.out.println("  --dry-run          validate everything, persist nothing");
        System.out.println("  --debug            verbose logging");
        System.out.println();
        System.out.println("All paths are resolved on the METHOD SERVER host, not on this workstation.");
        System.out.println();
        System.out.println("Exit codes: 0 clean, 1 row failures, 2 aborted, 3 usage error");
        System.out.println();
    }
}
