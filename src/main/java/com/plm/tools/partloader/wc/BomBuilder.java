package com.plm.tools.partloader.wc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.plm.tools.partloader.LoaderConfig;
import com.plm.tools.partloader.model.BomRow;
import com.plm.tools.partloader.model.RowResult;
import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.part.Quantity;
import wt.part.WTPart;
import wt.part.WTPartMaster;
import wt.part.WTPartUsageLink;
import wt.util.WTException;
import wt.vc.views.View;

/**
 * Builds parent/child structure ({@code wt.part.WTPartUsageLink}) for one parent at a time.
 *
 * <p><b>Why the input is grouped by parent before it gets here.</b> A usage link can only be
 * added while the parent is checked out. Processing BOM rows one at a time would check the
 * parent out and back in once per child, so a 60-line assembly would end up at iteration 60
 * with 60 entries in its history and 60 rows in the audit trail. Grouping means one checkout,
 * one checkin, one iteration per assembly — which is also what an engineer would expect to
 * see when they open the part history.</p>
 */
public class BomBuilder {

    private final ContextResolver context;
    private final String checkinNote;
    private final boolean updateExistingLinks;

    public BomBuilder(LoaderConfig config, ContextResolver context) {
        this.context = context;
        this.checkinNote = config.getString("loader.checkin.note", "Structure loaded by Part Loader utility");
        this.updateExistingLinks = config.getBoolean("loader.bom.updateExistingLinks", false);
    }

    /**
     * Processes every usage row belonging to one parent part.
     *
     * @return one {@link RowResult} per input row, in input order
     */
    public List<RowResult> processParent(String parentNumber, String viewName, List<BomRow> rows, boolean dryRun) {
        List<RowResult> results = new ArrayList<RowResult>(rows.size());

        WTPart parent;
        View view;
        try {
            view = context.resolveView(viewName);
            parent = PartLookup.findLatest(parentNumber, view);
        } catch (Exception e) {
            return failAll(rows, "Parent lookup failed: " + LoaderLog.describe(e));
        }
        if (parent == null) {
            return failAll(rows, "Parent part '" + parentNumber + "' does not exist in view '" + viewName
                    + "'. Load the parts file before the BOM file.");
        }

        // Resolve all children first. If any child is missing there is no point checking the
        // parent out: a partially built assembly is harder to reconcile than none at all.
        List<WTPartMaster> children = new ArrayList<WTPartMaster>(rows.size());
        for (BomRow row : rows) {
            try {
                WTPartMaster child = PartLookup.findMaster(row.getChildNumber());
                if (child == null) {
                    return failAll(rows, "Child part '" + row.getChildNumber() + "' (line " + row.getLineNumber()
                            + ") does not exist - no links created for parent " + parentNumber);
                }
                children.add(child);
            } catch (WTException e) {
                return failAll(rows, "Child lookup failed: " + LoaderLog.describe(e));
            }
        }

        Set<String> existingChildNumbers;
        try {
            existingChildNumbers = existingChildNumbers(parent);
        } catch (WTException e) {
            return failAll(rows, "Could not read existing structure: " + LoaderLog.describe(e));
        }

        if (dryRun) {
            for (int i = 0; i < rows.size(); i++) {
                BomRow row = rows.get(i);
                String key = parentNumber + " -> " + row.getChildNumber();
                boolean duplicate = existingChildNumbers.contains(row.getChildNumber());
                results.add(new RowResult(row.getLineNumber(), key, RowResult.Status.VALIDATED,
                        duplicate ? "link already exists - would be SKIPPED" : "link would be created"
                                + unappliedColumnNote(row)));
            }
            return results;
        }

        WTPart working = null;
        try {
            working = CheckoutUtil.checkout(parent, checkinNote);

            for (int i = 0; i < rows.size(); i++) {
                BomRow row = rows.get(i);
                WTPartMaster child = children.get(i);
                String key = parentNumber + " -> " + row.getChildNumber();

                if (existingChildNumbers.contains(row.getChildNumber()) && !updateExistingLinks) {
                    results.add(new RowResult(row.getLineNumber(), key, RowResult.Status.SKIPPED,
                            "Usage link already exists; loader.bom.updateExistingLinks is false"));
                    continue;
                }

                try {
                    WTPartUsageLink link = WTPartUsageLink.newWTPartUsageLink(working, child);
                    Quantity.QuantityUnit unit = EnumResolver.toUnit(row.getUnit());
                    link.setAmount(row.getQuantity());
                    if (unit != null) {
                        link.setUnit(unit);
                    }
                    PersistenceHelper.manager.store(link);
                    existingChildNumbers.add(row.getChildNumber());
                    results.add(new RowResult(row.getLineNumber(), key, RowResult.Status.CREATED,
                            "qty " + row.getQuantity() + " " + row.getUnit() + unappliedColumnNote(row)));
                } catch (Exception e) {
                    results.add(new RowResult(row.getLineNumber(), key, RowResult.Status.FAILED,
                            LoaderLog.describe(e)));
                }
            }

            CheckoutUtil.checkin(working, checkinNote);
            working = null;
            return results;

        } catch (LoaderException e) {
            return failAll(rows, e.getMessage());
        } catch (Exception e) {
            return failAll(rows, LoaderLog.describe(e));
        } finally {
            CheckoutUtil.undoCheckout(working);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Part numbers of the children already used by this parent.
     *
     * <p>Passing {@code false} as the last argument returns the link objects rather than the
     * far-side objects, which is what lets a future extension update an existing link's
     * quantity instead of only detecting it.</p>
     */
    private Set<String> existingChildNumbers(WTPart parent) throws WTException {
        Set<String> numbers = new HashSet<String>();
        QueryResult qr = PersistenceHelper.manager.navigate(parent, WTPartUsageLink.USES_ROLE,
                WTPartUsageLink.class, false);
        while (qr.hasMoreElements()) {
            WTPartUsageLink link = (WTPartUsageLink) qr.nextElement();
            WTPartMaster used = link.getUses();
            if (used != null) {
                numbers.add(used.getNumber());
            }
        }
        return numbers;
    }

    /**
     * Find number and line number are parsed and reported but not written — see
     * docs/API_VERIFICATION.md item 5. Reporting the gap is deliberate: silently ignoring a
     * populated column is how a loader convinces a business owner that data was loaded when
     * it was not.
     */
    private static String unappliedColumnNote(BomRow row) {
        boolean hasFind = row.getFindNumber() != null && row.getFindNumber().length() > 0;
        boolean hasLine = row.getLineNumberValue() != null && row.getLineNumberValue().length() > 0;
        if (!hasFind && !hasLine) {
            return "";
        }
        return " [WARNING: findNumber/lineNumber supplied but NOT applied by this build]";
    }

    private static List<RowResult> failAll(List<BomRow> rows, String message) {
        List<RowResult> results = new ArrayList<RowResult>(rows.size());
        for (BomRow row : rows) {
            results.add(new RowResult(row.getLineNumber(),
                    row.getParentNumber() + " -> " + row.getChildNumber(),
                    RowResult.Status.FAILED, message));
        }
        return results;
    }
}
