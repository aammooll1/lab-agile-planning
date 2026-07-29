package com.plm.tools.partloader.util;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Deliberately dependency-free logger.
 *
 * <p>Windchill ships its own logging stack and the exact API has changed between
 * releases (log4j 1.x bridge, log4j2, slf4j facades). Binding a loader to whichever
 * one happens to be on the codebase classpath is a recurring source of
 * NoClassDefFoundError after service packs, so this utility writes to stdout/stderr
 * instead. When executed inside the method server those streams land in
 * {@code $WT_HOME/logs/MethodServer-*.log}; when executed from the {@code windchill}
 * shell they land on the operator console.</p>
 */
public final class LoaderLog {

    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static boolean debugEnabled = Boolean.getBoolean("partloader.debug");

    private LoaderLog() {
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void info(String message) {
        write(System.out, "INFO ", message, null);
    }

    public static void warn(String message) {
        write(System.out, "WARN ", message, null);
    }

    public static void error(String message, Throwable t) {
        write(System.err, "ERROR", message, t);
    }

    public static void debug(String message) {
        if (debugEnabled) {
            write(System.out, "DEBUG", message, null);
        }
    }

    private static synchronized void write(PrintStream out, String level, String message, Throwable t) {
        out.println(TS.format(new Date()) + " [" + level + "] [PartLoader] " + message);
        if (t != null) {
            t.printStackTrace(out);
        }
        out.flush();
    }

    /** Renders the full cause chain on a single line, which is what an operator actually needs. */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int guard = 0;
        while (current != null && guard++ < 10) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage().replace('\n', ' ').replace('\r', ' '));
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return sb.toString();
    }
}
