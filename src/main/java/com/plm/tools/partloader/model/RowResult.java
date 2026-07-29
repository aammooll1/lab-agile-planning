package com.plm.tools.partloader.model;

import java.io.Serializable;

/**
 * Outcome of a single input row. Every row produces exactly one of these, including
 * successful ones — a loader that only reports failures cannot be reconciled against the
 * source file during data-migration validation, and reconciliation is what the business
 * signs off on.
 */
public class RowResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status {
        /** Object did not exist and was created. */
        CREATED,
        /** Object existed and was revised/updated in place. */
        UPDATED,
        /** Object existed and was intentionally left untouched. */
        SKIPPED,
        /** Row passed validation in dry-run mode; nothing was persisted. */
        VALIDATED,
        /** Row could not be processed. */
        FAILED
    }

    private final long lineNumber;
    private final String key;
    private final Status status;
    private final String message;

    public RowResult(long lineNumber, String key, Status status, String message) {
        this.lineNumber = lineNumber;
        this.key = key == null ? "" : key;
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public long getLineNumber() {
        return lineNumber;
    }

    public String getKey() {
        return key;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public boolean isFailure() {
        return status == Status.FAILED;
    }

    @Override
    public String toString() {
        return "line " + lineNumber + " [" + key + "] " + status + (message.length() > 0 ? " - " + message : "");
    }
}
