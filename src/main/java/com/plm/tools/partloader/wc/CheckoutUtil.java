package com.plm.tools.partloader.wc;

import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import wt.folder.Folder;
import wt.part.WTPart;
import wt.util.WTException;
import wt.vc.wip.CheckoutLink;
import wt.vc.wip.WorkInProgressHelper;
import wt.vc.wip.Workable;

/**
 * Check-out / check-in wrapper used by the update path.
 *
 * <p>Windchill will not let you modify a checked-in iteration, so updating an existing part
 * means checkout, modify, checkin — and every one of those steps can fail for reasons the
 * operator needs stated plainly rather than as a stack trace. The most common in practice is
 * "already checked out by a designer", which is not a loader defect but a scheduling problem:
 * mass updates belong in a window when engineering is not working in the affected products.</p>
 */
public final class CheckoutUtil {

    private CheckoutUtil() {
    }

    public static boolean isCheckedOut(WTPart part) throws WTException {
        return WorkInProgressHelper.isCheckedOut(part);
    }

    /**
     * Checks the part out into the executing user's personal checkout folder and returns the
     * working copy.
     */
    public static WTPart checkout(WTPart part, String note) throws WTException, LoaderException {
        if (WorkInProgressHelper.isCheckedOut(part)) {
            throw new LoaderException("Part " + part.getNumber() + " is already checked out"
                    + describeHolder(part) + " - skipped to avoid overwriting work in progress");
        }
        Folder checkoutFolder = WorkInProgressHelper.service.getCheckoutFolder();

        // Windchill 12.x returns a CheckoutLink. Older customisations often assume the
        // Workable itself is returned; if this line does not compile against your codebase,
        // substitute:
        //     WTPart working = (WTPart) WorkInProgressHelper.service.checkout(part, checkoutFolder, note);
        CheckoutLink link = WorkInProgressHelper.service.checkout(part, checkoutFolder, note);
        return (WTPart) link.getWorkingCopy();
    }

    /** Checks the working copy back in and returns the resulting new iteration. */
    public static WTPart checkin(WTPart workingCopy, String note) throws WTException {
        Workable checkedIn = WorkInProgressHelper.service.checkin(workingCopy, note);
        return (WTPart) checkedIn;
    }

    /**
     * Undoes a checkout after a failed update. Deliberately swallows its own failure: the
     * exception the caller is already handling is the one worth reporting.
     */
    public static void undoCheckout(WTPart workingCopy) {
        if (workingCopy == null) {
            return;
        }
        try {
            WorkInProgressHelper.service.undoCheckout(workingCopy);
        } catch (Exception e) {
            LoaderLog.warn("Undo checkout failed; part may remain checked out: " + LoaderLog.describe(e));
        }
    }

    /** Best-effort " by <user>" suffix for the error message. Never throws. */
    private static String describeHolder(WTPart part) {
        try {
            Workable working = WorkInProgressHelper.service.workingCopyOf(part);
            if (working != null) {
                return " by " + working.getPersistInfo().getCreator().getFullName();
            }
        } catch (Exception ignored) {
            // Diagnostic detail only.
        }
        return "";
    }
}
