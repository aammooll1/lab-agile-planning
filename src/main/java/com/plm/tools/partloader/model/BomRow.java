package com.plm.tools.partloader.model;

import java.io.Serializable;

/**
 * One parent/child usage relationship (a {@code wt.part.WTPartUsageLink}).
 *
 * <p>Structure is deliberately loaded in a second pass, after all masters exist. Loading
 * parts and structure in a single pass forces the file to be topologically sorted, which
 * no business user reliably produces and which breaks the moment a child is reused across
 * two assemblies.</p>
 */
public class BomRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private long lineNumber;

    private String parentNumber;
    private String parentView;
    private String childNumber;

    private double quantity = 1.0d;
    private String unit;
    private String lineNumberValue;
    private String findNumber;

    // ---------------------------------------------------------------- accessors

    public long getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(long lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getParentNumber() {
        return parentNumber;
    }

    public void setParentNumber(String parentNumber) {
        this.parentNumber = parentNumber;
    }

    public String getParentView() {
        return parentView;
    }

    public void setParentView(String parentView) {
        this.parentView = parentView;
    }

    public String getChildNumber() {
        return childNumber;
    }

    public void setChildNumber(String childNumber) {
        this.childNumber = childNumber;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getLineNumberValue() {
        return lineNumberValue;
    }

    public void setLineNumberValue(String lineNumberValue) {
        this.lineNumberValue = lineNumberValue;
    }

    public String getFindNumber() {
        return findNumber;
    }

    public void setFindNumber(String findNumber) {
        this.findNumber = findNumber;
    }

    @Override
    public String toString() {
        return "BomRow[line=" + lineNumber + ", parent=" + parentNumber + ", child=" + childNumber
                + ", qty=" + quantity + "]";
    }
}
