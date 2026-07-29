package com.plm.tools.partloader.io;

import java.util.Map;

import com.plm.tools.partloader.LoaderConfig;
import com.plm.tools.partloader.model.BomRow;
import com.plm.tools.partloader.model.PartRow;
import com.plm.tools.partloader.util.LoaderException;

/**
 * Converts raw CSV records into validated, defaulted domain rows.
 *
 * <p>All defaulting happens here rather than deep in the Windchill layer. That separation
 * is what makes dry-run meaningful: by the time a {@link PartRow} reaches the persistence
 * code every value it will use is already decided and visible in the report.</p>
 */
public final class RowMapper {

    // Recognised part-file columns. Anything else that is not an IBA: column is ignored,
    // which lets the business hand over their working spreadsheet without stripping the
    // planning columns they use for their own tracking.
    public static final String COL_NUMBER = "number";
    public static final String COL_NAME = "name";
    public static final String COL_CONTAINER_TYPE = "containerType";
    public static final String COL_CONTAINER_NAME = "containerName";
    public static final String COL_FOLDER = "folderPath";
    public static final String COL_VIEW = "view";
    public static final String COL_PART_TYPE = "partType";
    public static final String COL_SOURCE = "source";
    public static final String COL_UNIT = "defaultUnit";
    public static final String COL_TRACE_CODE = "traceCode";
    public static final String COL_SOFT_TYPE = "softType";
    public static final String COL_STATE = "state";

    // BOM file columns
    public static final String COL_PARENT_NUMBER = "parentNumber";
    public static final String COL_PARENT_VIEW = "parentView";
    public static final String COL_CHILD_NUMBER = "childNumber";
    public static final String COL_QUANTITY = "quantity";
    public static final String COL_LINE_NUMBER = "lineNumber";
    public static final String COL_FIND_NUMBER = "findNumber";

    private final LoaderConfig config;

    public RowMapper(LoaderConfig config) {
        this.config = config;
    }

    // ---------------------------------------------------------------- parts

    public PartRow toPartRow(Map<String, String> record, long lineNumber) throws LoaderException {
        PartRow row = new PartRow();
        row.setLineNumber(lineNumber);

        row.setNumber(required(record, COL_NUMBER, lineNumber));
        row.setName(value(record, COL_NAME, null));
        if (row.getName() == null) {
            // Windchill will not create a part without a name, and defaulting it to the
            // number produces unusable search results downstream. Fail the row instead.
            throw new LoaderException("Column '" + COL_NAME + "' is mandatory and is empty");
        }

        row.setContainerType(value(record, COL_CONTAINER_TYPE, config.getDefaultContainerType()).toUpperCase());
        row.setContainerName(value(record, COL_CONTAINER_NAME, config.getDefaultContainerName()));
        if (row.getContainerName() == null) {
            throw new LoaderException("No container: column '" + COL_CONTAINER_NAME
                    + "' is empty and loader.default.containerName is not configured");
        }

        row.setFolderPath(normaliseFolder(value(record, COL_FOLDER, config.getDefaultFolderPath())));
        row.setView(value(record, COL_VIEW, config.getDefaultView()));
        row.setPartType(value(record, COL_PART_TYPE, config.getDefaultPartType()));
        row.setSource(value(record, COL_SOURCE, config.getDefaultSource()));
        row.setDefaultUnit(value(record, COL_UNIT, config.getDefaultUnit()));
        row.setTraceCode(value(record, COL_TRACE_CODE, config.getDefaultTraceCode()));
        row.setSoftType(value(record, COL_SOFT_TYPE, null));
        row.setState(value(record, COL_STATE, null));

        for (Map.Entry<String, String> e : record.entrySet()) {
            String header = e.getKey();
            if (header != null && header.startsWith(LoaderConfig.IBA_COLUMN_PREFIX)) {
                String attribute = header.substring(LoaderConfig.IBA_COLUMN_PREFIX.length()).trim();
                String v = e.getValue();
                // An empty IBA cell means "no value supplied", not "clear the value".
                // Clearing on blank is a classic loader defect: a partial re-load then
                // silently wipes attributes that were maintained manually in the UI.
                if (attribute.length() > 0 && v != null && v.trim().length() > 0) {
                    row.putIba(attribute, v.trim());
                }
            }
        }
        return row;
    }

    // ---------------------------------------------------------------- BOM

    public BomRow toBomRow(Map<String, String> record, long lineNumber) throws LoaderException {
        BomRow row = new BomRow();
        row.setLineNumber(lineNumber);
        row.setParentNumber(required(record, COL_PARENT_NUMBER, lineNumber));
        row.setChildNumber(required(record, COL_CHILD_NUMBER, lineNumber));
        row.setParentView(value(record, COL_PARENT_VIEW, config.getDefaultView()));
        row.setUnit(value(record, COL_UNIT, config.getDefaultUnit()));
        row.setLineNumberValue(value(record, COL_LINE_NUMBER, null));
        row.setFindNumber(value(record, COL_FIND_NUMBER, null));

        String qty = value(record, COL_QUANTITY, "1");
        try {
            double q = Double.parseDouble(qty);
            if (q <= 0) {
                throw new LoaderException("Quantity must be greater than zero, found '" + qty + "'");
            }
            row.setQuantity(q);
        } catch (NumberFormatException nfe) {
            throw new LoaderException("Quantity '" + qty + "' is not a number");
        }

        if (row.getParentNumber().equals(row.getChildNumber())) {
            throw new LoaderException("Self-referencing usage link: parent equals child (" + row.getParentNumber() + ")");
        }
        return row;
    }

    // ---------------------------------------------------------------- helpers

    private static String required(Map<String, String> record, String column, long line) throws LoaderException {
        String v = record.get(column);
        if (v == null || v.trim().length() == 0) {
            throw new LoaderException("Mandatory column '" + column + "' is missing or empty");
        }
        return v.trim();
    }

    private static String value(Map<String, String> record, String column, String defaultValue) {
        String v = record.get(column);
        return (v == null || v.trim().length() == 0) ? defaultValue : v.trim();
    }

    /** Windchill folder paths are absolute within the container and start with '/Default'. */
    private static String normaliseFolder(String path) {
        if (path == null) {
            return "/Default";
        }
        String p = path.trim().replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        // Users habitually type "Parts" or "/Parts" when they mean "/Default/Parts",
        // because the UI folder tree hides the Default cabinet root.
        if (!p.equals("/Default") && !p.startsWith("/Default/")) {
            p = "/Default" + p;
        }
        return p;
    }
}
