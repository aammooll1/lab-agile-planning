package com.plm.tools.partloader.io;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal streaming RFC 4180 CSV reader.
 *
 * <p>Written by hand on purpose. Dropping OpenCSV / commons-csv / POI into
 * {@code WEB-INF/lib} to read a load file is one of the most common ways a customisation
 * destabilises a Windchill installation: the jar collides with a PTC-shipped version, the
 * conflict surfaces months later as a class-loading failure in an unrelated module, and
 * the next service pack silently changes which version wins. A loader needs about 80 lines
 * of parsing; it does not need a dependency in the codebase.</p>
 *
 * <p>Supports quoted fields, embedded delimiters, embedded newlines, doubled quotes as an
 * escape, and a UTF-8 BOM on the first cell.</p>
 */
public class CsvReader implements Closeable {

    private final Reader in;
    private final char delimiter;

    private List<String> headers;
    private long physicalLine = 0;
    private int pushedBack = -2;
    private boolean eof = false;

    public CsvReader(File file, String charset, char delimiter) throws IOException {
        this.in = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset), 64 * 1024);
        this.delimiter = delimiter;
    }

    /**
     * Reads the header row and returns the column names, trimmed. Header names are kept
     * verbatim (case included) so that {@code IBA:} column names match the internal
     * attribute names exactly — Windchill IBA lookup is case sensitive.
     */
    public List<String> readHeaders() throws IOException {
        List<String> row = readRow();
        if (row == null) {
            throw new IOException("Input file is empty - no header row found");
        }
        List<String> cleaned = new ArrayList<String>(row.size());
        for (int i = 0; i < row.size(); i++) {
            String h = row.get(i);
            if (i == 0) {
                h = stripBom(h);
            }
            cleaned.add(h.trim());
        }
        this.headers = cleaned;
        return cleaned;
    }

    /**
     * Reads the next data record as a column-name to value map, or null at end of file.
     * Blank lines are skipped rather than reported as errors — trailing blank lines are
     * near universal in files exported from Excel.
     */
    public Map<String, String> readRecord() throws IOException {
        if (headers == null) {
            readHeaders();
        }
        List<String> row;
        while ((row = readRow()) != null) {
            if (isBlank(row)) {
                continue;
            }
            Map<String, String> record = new LinkedHashMap<String, String>();
            for (int i = 0; i < headers.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                record.put(headers.get(i), value == null ? "" : value.trim());
            }
            return record;
        }
        return null;
    }

    /** Physical line number of the record most recently returned. */
    public long getLineNumber() {
        return physicalLine;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ---------------------------------------------------------------- parsing

    private List<String> readRow() throws IOException {
        if (eof) {
            return null;
        }
        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;

        while (true) {
            int c = read();
            if (c == -1) {
                eof = true;
                if (!any && field.length() == 0 && fields.isEmpty()) {
                    return null;
                }
                fields.add(field.toString());
                physicalLine++;
                return fields;
            }
            any = true;

            if (inQuotes) {
                if (c == '"') {
                    int next = read();
                    if (next == '"') {
                        field.append('"');      // escaped quote
                    } else {
                        inQuotes = false;
                        pushBack(next);
                    }
                } else {
                    field.append((char) c);
                }
                continue;
            }

            if (c == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                int next = read();
                if (next != '\n') {
                    pushBack(next);
                }
                fields.add(field.toString());
                physicalLine++;
                return fields;
            } else if (c == '\n') {
                fields.add(field.toString());
                physicalLine++;
                return fields;
            } else {
                field.append((char) c);
            }
        }
    }

    private int read() throws IOException {
        if (pushedBack != -2) {
            int c = pushedBack;
            pushedBack = -2;
            return c;
        }
        return in.read();
    }

    private void pushBack(int c) {
        pushedBack = c;
    }

    private static boolean isBlank(List<String> row) {
        for (String s : row) {
            if (s != null && s.trim().length() > 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripBom(String s) {
        return (s != null && s.length() > 0 && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }
}
