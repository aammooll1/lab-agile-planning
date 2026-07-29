package com.plm.tools.partloader.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One logical part record from the input file, already normalised and defaulted.
 *
 * <p>Hard attributes are modelled as explicit fields because they map to typed Windchill
 * setters. Soft attributes (IBAs) stay in a map because they are configuration driven and
 * differ per implementation — in a typical automotive Tier-1 rollout the soft attribute set
 * for a part is 20 to 60 attributes and changes with every release of the data model.</p>
 */
public class PartRow implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 1-based physical line number in the source file, for the error report. */
    private long lineNumber;

    private String number;
    private String name;
    private String containerType;
    private String containerName;
    private String folderPath;
    private String view;
    private String partType;
    private String source;
    private String defaultUnit;
    private String traceCode;

    /** Fully qualified soft type, e.g. {@code com.acme.PurchasedPart}. Null = plain WTPart. */
    private String softType;

    /** Internal lifecycle state name. Only honoured when direct state setting is enabled. */
    private String state;

    // Concrete type: PartRow is Serializable and the map travels with it.
    private final LinkedHashMap<String, String> ibaValues = new LinkedHashMap<String, String>();

    // ---------------------------------------------------------------- accessors

    public long getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(long lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContainerType() {
        return containerType;
    }

    public void setContainerType(String containerType) {
        this.containerType = containerType;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getView() {
        return view;
    }

    public void setView(String view) {
        this.view = view;
    }

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDefaultUnit() {
        return defaultUnit;
    }

    public void setDefaultUnit(String defaultUnit) {
        this.defaultUnit = defaultUnit;
    }

    public String getTraceCode() {
        return traceCode;
    }

    public void setTraceCode(String traceCode) {
        this.traceCode = traceCode;
    }

    public String getSoftType() {
        return softType;
    }

    public void setSoftType(String softType) {
        this.softType = softType;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, String> getIbaValues() {
        return ibaValues;
    }

    public void putIba(String name, String value) {
        ibaValues.put(name, value);
    }

    @Override
    public String toString() {
        return "PartRow[line=" + lineNumber + ", number=" + number + ", view=" + view
                + ", container=" + containerType + ":" + containerName + "]";
    }
}
