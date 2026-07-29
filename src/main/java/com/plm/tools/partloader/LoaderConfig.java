package com.plm.tools.partloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Properties;

/**
 * Runtime configuration for the part loader.
 *
 * <p>Loaded from a standard java.util.Properties file so that the same binary can be
 * promoted DEV -> QA -> PROD with nothing but a configuration change. Nothing in this
 * class touches Windchill APIs, which keeps it unit-testable outside the method server.</p>
 */
public class LoaderConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Prefix used in the CSV header to mark a soft (IBA) attribute column. */
    public static final String IBA_COLUMN_PREFIX = "IBA:";

    private final Properties props = new Properties();

    // ---------------------------------------------------------------- lifecycle

    private LoaderConfig() {
    }

    public static LoaderConfig load(String path) throws IOException {
        LoaderConfig cfg = new LoaderConfig();
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("Configuration file not found on the server host: " + file.getAbsolutePath());
        }
        InputStream in = new FileInputStream(file);
        try {
            cfg.props.load(in);
        } finally {
            in.close();
        }
        return cfg;
    }

    /** Configuration used by unit tests / callers that build values programmatically. */
    public static LoaderConfig empty() {
        return new LoaderConfig();
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    // ---------------------------------------------------------------- accessors

    public String getString(String key, String defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.trim().length() == 0) ? defaultValue : v.trim();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.trim().length() == 0) ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public int getInt(String key, int defaultValue) {
        String v = props.getProperty(key);
        if (v == null || v.trim().length() == 0) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ---------------------------------------------------------------- typed shortcuts

    public String getCharset() {
        return getString("loader.csv.charset", "UTF-8");
    }

    public char getDelimiter() {
        String d = getString("loader.csv.delimiter", ",");
        return d.length() == 0 ? ',' : d.charAt(0);
    }

    public String getReportDirectory() {
        return getString("loader.report.dir", ".");
    }

    /** Container type when the CSV omits it: PRODUCT | LIBRARY | ORG. */
    public String getDefaultContainerType() {
        return getString("loader.default.containerType", "PRODUCT");
    }

    public String getDefaultContainerName() {
        return getString("loader.default.containerName", null);
    }

    public String getDefaultFolderPath() {
        return getString("loader.default.folderPath", "/Default");
    }

    public String getDefaultView() {
        return getString("loader.default.view", "Design");
    }

    public String getDefaultSource() {
        return getString("loader.default.source", "make");
    }

    public String getDefaultUnit() {
        return getString("loader.default.unit", "ea");
    }

    public String getDefaultPartType() {
        return getString("loader.default.partType", "separable");
    }

    public String getDefaultTraceCode() {
        return getString("loader.default.traceCode", null);
    }

    /** When true, an existing part is checked out, updated and checked back in. */
    public boolean isUpdateExisting() {
        return getBoolean("loader.update.existing", false);
    }

    /** When true, a missing folder path is created rather than failing the row. */
    public boolean isAutoCreateFolders() {
        return getBoolean("loader.folder.autoCreate", false);
    }

    /** When true, the loader aborts on the first failed row instead of continuing. */
    public boolean isStopOnError() {
        return getBoolean("loader.stopOnError", false);
    }

    /** Number of rows between progress log lines. */
    public int getProgressInterval() {
        return getInt("loader.progress.interval", 100);
    }

    /** Maximum number of row failures tolerated before the run is aborted. -1 = unlimited. */
    public int getMaxErrors() {
        return getInt("loader.maxErrors", -1);
    }
}
