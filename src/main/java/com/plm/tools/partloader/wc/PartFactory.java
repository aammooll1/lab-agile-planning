package com.plm.tools.partloader.wc;

import com.plm.tools.partloader.LoaderConfig;
import com.plm.tools.partloader.model.PartRow;
import com.plm.tools.partloader.model.RowResult;
import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import wt.fc.PersistenceHelper;
import wt.folder.Folder;
import wt.folder.FolderEntry;
import wt.folder.FolderHelper;
import wt.inf.container.WTContainerRef;
import wt.part.PartType;
import wt.part.Quantity;
import wt.part.Source;
import wt.part.TraceCode;
import wt.part.WTPart;
import wt.part.WTPartMaster;
import wt.util.WTException;
import wt.vc.views.View;

/**
 * Creates or updates a single WTPart from a {@link PartRow}.
 *
 * <p>This is the only class that writes part objects. Everything it needs has already been
 * resolved and defaulted by the caller, so the logic here stays readable: resolve context,
 * decide create vs update vs skip, apply attributes, persist.</p>
 */
public class PartFactory {

    private final LoaderConfig config;
    private final ContextResolver context;
    private final SoftTypeResolver softTypes = new SoftTypeResolver();

    private final boolean ibaStrict;
    private final boolean allowStateAssignment;
    private final String checkinNote;

    public PartFactory(LoaderConfig config, ContextResolver context) {
        this.config = config;
        this.context = context;
        this.ibaStrict = config.getBoolean("loader.iba.strict", true);
        this.allowStateAssignment = config.getBoolean("loader.lifecycle.allowDirectStateAssignment", false);
        this.checkinNote = config.getString("loader.checkin.note", "Updated by Part Loader utility");
    }

    /**
     * Processes one row. Never throws for row-level problems — those become a FAILED
     * {@link RowResult} so that the run continues and the report stays complete. Only
     * programming errors escape.
     */
    public RowResult process(PartRow row, boolean dryRun) {
        String key = row.getNumber() + " / " + row.getView();
        try {
            WTContainerRef containerRef = context.resolveContainer(row.getContainerType(), row.getContainerName());
            Folder folder = context.resolveFolder(containerRef, row.getFolderPath());
            View view = context.resolveView(row.getView());

            WTPartMaster master = PartLookup.findMaster(row.getNumber());
            WTPart existing = PartLookup.findLatest(master, view);

            if (dryRun) {
                return validate(row, key, existing);
            }

            if (existing != null) {
                if (!config.isUpdateExisting()) {
                    return new RowResult(row.getLineNumber(), key, RowResult.Status.SKIPPED,
                            "Part already exists (" + existing.getVersionIdentifier().getValue() + "."
                                    + existing.getIterationIdentifier().getValue()
                                    + "); loader.update.existing is false");
                }
                return update(row, key, existing);
            }
            return create(row, key, containerRef, folder, view);

        } catch (LoaderException e) {
            return new RowResult(row.getLineNumber(), key, RowResult.Status.FAILED, e.getMessage());
        } catch (Exception e) {
            LoaderLog.debug("Unexpected failure on " + row + ": " + LoaderLog.describe(e));
            return new RowResult(row.getLineNumber(), key, RowResult.Status.FAILED, LoaderLog.describe(e));
        }
    }

    // ---------------------------------------------------------------- create

    private RowResult create(PartRow row, String key, WTContainerRef containerRef, Folder folder, View view)
            throws WTException, LoaderException {

        WTPart part = WTPart.newWTPart();

        // Soft type must be assigned before store: the type identifier determines which
        // OIR numbering rule and attribute set apply to the object being created.
        softTypes.apply(part, row.getSoftType());

        part.setNumber(row.getNumber());
        part.setName(row.getName());
        part.setContainerReference(containerRef);
        part.setView(view);

        applyHardAttributes(part, row);
        FolderHelper.assignLocation((FolderEntry) part, folder);

        part = (WTPart) PersistenceHelper.manager.store(part);

        int ibaCount = IbaWriter.apply(part, row.getIbaValues(), ibaStrict);
        if (ibaCount > 0) {
            part = (WTPart) PersistenceHelper.manager.modify(part);
        }

        String stateNote = applyStateIfRequested(part, row);

        return new RowResult(row.getLineNumber(), key, RowResult.Status.CREATED,
                "Created " + part.getVersionIdentifier().getValue() + "."
                        + part.getIterationIdentifier().getValue()
                        + ", " + ibaCount + " soft attribute(s)" + stateNote);
    }

    // ---------------------------------------------------------------- update

    private RowResult update(PartRow row, String key, WTPart existing) throws WTException, LoaderException {
        WTPart working = null;
        try {
            working = CheckoutUtil.checkout(existing, checkinNote);

            working.setName(row.getName());
            applyHardAttributes(working, row);
            working = (WTPart) PersistenceHelper.manager.modify(working);

            int ibaCount = IbaWriter.apply(working, row.getIbaValues(), ibaStrict);
            if (ibaCount > 0) {
                working = (WTPart) PersistenceHelper.manager.modify(working);
            }

            WTPart checkedIn = CheckoutUtil.checkin(working, checkinNote);
            working = null;

            String stateNote = applyStateIfRequested(checkedIn, row);

            return new RowResult(row.getLineNumber(), key, RowResult.Status.UPDATED,
                    "Updated to " + checkedIn.getVersionIdentifier().getValue() + "."
                            + checkedIn.getIterationIdentifier().getValue()
                            + ", " + ibaCount + " soft attribute(s)" + stateNote);
        } finally {
            // Reached only when the update failed after a successful checkout. Leaving
            // orphaned working copies behind is worse than the original failure: they block
            // every subsequent run and the designer who owns the part cannot check it out.
            CheckoutUtil.undoCheckout(working);
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Applies the typed WTPart attributes. Null values are left untouched rather than
     * cleared, which keeps a partial load file from wiping attributes it does not mention.
     */
    private void applyHardAttributes(WTPart part, PartRow row) throws WTException, LoaderException {
        Source source = EnumResolver.toSource(row.getSource());
        if (source != null) {
            part.setSource(source);
        }
        PartType partType = EnumResolver.toPartType(row.getPartType());
        if (partType != null) {
            part.setPartType(partType);
        }
        Quantity.QuantityUnit unit = EnumResolver.toUnit(row.getDefaultUnit());
        if (unit != null) {
            part.setDefaultUnit(unit);
        }
        TraceCode traceCode = EnumResolver.toTraceCode(row.getTraceCode());
        if (traceCode != null) {
            part.setDefaultTraceCode(traceCode);
        }
    }

    /** @return a note fragment for the report, empty when no state was assigned */
    private String applyStateIfRequested(WTPart part, PartRow row) throws WTException, LoaderException {
        if (row.getState() == null || row.getState().length() == 0) {
            return "";
        }
        if (!allowStateAssignment) {
            LoaderLog.warn("Row " + row.getLineNumber() + " requests state '" + row.getState()
                    + "' but loader.lifecycle.allowDirectStateAssignment is false - state ignored");
            return ", state IGNORED (direct state assignment disabled)";
        }
        LifecycleUtil.setState(part, row.getState());
        return ", state set to " + row.getState() + " WITHOUT workflow";
    }

    // ---------------------------------------------------------------- dry run

    private RowResult validate(PartRow row, String key, WTPart existing) {
        StringBuilder notes = new StringBuilder();
        try {
            EnumResolver.toSource(row.getSource());
            EnumResolver.toPartType(row.getPartType());
            EnumResolver.toUnit(row.getDefaultUnit());
            EnumResolver.toTraceCode(row.getTraceCode());
        } catch (LoaderException e) {
            return new RowResult(row.getLineNumber(), key, RowResult.Status.FAILED, e.getMessage());
        }

        String ibaProblems = IbaWriter.validate(SoftTypeResolver.toTypeIdentifierString(row.getSoftType()),
                row.getIbaValues());
        if (ibaProblems != null) {
            // Unknown attributes are a hard stop: they mean the load file was built against a
            // different version of the data model than the target environment runs.
            if (ibaProblems.startsWith("unknown attribute")) {
                return new RowResult(row.getLineNumber(), key, RowResult.Status.FAILED, ibaProblems);
            }
            notes.append(ibaProblems);
        }

        if (existing != null) {
            append(notes, config.isUpdateExisting()
                    ? "exists - would be checked out and updated"
                    : "exists - would be SKIPPED");
        } else {
            append(notes, "would be created");
        }
        if (row.getState() != null && row.getState().length() > 0 && !allowStateAssignment) {
            append(notes, "state column present but direct state assignment is disabled");
        }
        return new RowResult(row.getLineNumber(), key, RowResult.Status.VALIDATED, notes.toString());
    }

    private static void append(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(text);
    }

    public void clearCaches() {
        softTypes.clear();
    }
}
