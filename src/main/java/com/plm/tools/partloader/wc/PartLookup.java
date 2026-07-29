package com.plm.tools.partloader.wc;

import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.part.WTPart;
import wt.part.WTPartMaster;
import wt.part.WTPartStandardConfigSpec;
import wt.query.QuerySpec;
import wt.query.SearchCondition;
import wt.util.WTException;
import wt.vc.config.ConfigHelper;
import wt.vc.views.View;

/**
 * Existence checks for parts.
 *
 * <p>Every enterprise load is a re-load. Files get corrected and resubmitted, migrations run
 * in waves, and the same golden data set is replayed into DEV, QA and PROD. A loader that
 * cannot answer "does this already exist, and in which view" produces duplicate masters,
 * and duplicate part masters in Windchill are expensive to unwind because they cannot
 * simply be deleted once they are referenced by structure, documents or change objects.</p>
 */
public final class PartLookup {

    private PartLookup() {
    }

    /**
     * Finds the part master by number. Part numbers are unique across the whole database
     * in a standard Windchill installation, independent of container.
     *
     * @return the master, or null when no part with that number exists
     */
    public static WTPartMaster findMaster(String number) throws WTException {
        QuerySpec qs = new QuerySpec(WTPartMaster.class);
        qs.appendWhere(new SearchCondition(WTPartMaster.class, WTPartMaster.NUMBER, SearchCondition.EQUAL, number),
                new int[] { 0 });
        QueryResult qr = PersistenceHelper.manager.find(qs);
        return qr.hasMoreElements() ? (WTPartMaster) qr.nextElement() : null;
    }

    /**
     * Returns the latest iteration of the given master in the given view, or null when the
     * master exists but has no version in that view.
     *
     * <p>A part can legitimately exist in Design and not in Manufacturing. Treating "master
     * exists" as "part exists" is the defect that silently skips the Manufacturing branch of
     * an MPMLink rollout.</p>
     */
    public static WTPart findLatest(WTPartMaster master, View view) throws WTException {
        if (master == null) {
            return null;
        }
        // A view-aware config spec resolves the latest iteration of the latest version in
        // the requested view, honouring view branching rules, which a raw
        // allVersionsOf() + "take the last one" loop does not.
        WTPartStandardConfigSpec configSpec = WTPartStandardConfigSpec.newWTPartStandardConfigSpec(view, null);
        QueryResult qr = ConfigHelper.service.filteredIterationsOf(master, configSpec);
        return qr.hasMoreElements() ? (WTPart) qr.nextElement() : null;
    }

    /** Convenience: latest iteration by number and view in one call. */
    public static WTPart findLatest(String number, View view) throws WTException {
        return findLatest(findMaster(number), view);
    }
}
