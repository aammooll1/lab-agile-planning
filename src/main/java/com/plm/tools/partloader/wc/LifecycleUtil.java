package com.plm.tools.partloader.wc;

import com.plm.tools.partloader.util.LoaderException;

import wt.lifecycle.LifeCycleHelper;
import wt.lifecycle.LifeCycleManaged;
import wt.lifecycle.State;
import wt.util.WTException;

/**
 * Optional direct lifecycle state assignment.
 *
 * <p><b>Read this before enabling it.</b> Setting a state directly bypasses the lifecycle:
 * no workflow runs, no approvals are recorded, no promotion request exists, and the object's
 * history will show the state changing with no corresponding activity. For a genuine legacy
 * data migration that is exactly what you want — you are asserting that 40 000 parts were
 * approved years ago in the old system and re-approving them through workflow is neither
 * possible nor meaningful.</p>
 *
 * <p>For anything that is not a migration it is almost always the wrong answer, and in an
 * IATF 16949 or ISO 9001 audited environment it is the kind of shortcut that turns into a
 * finding. Hence: disabled by default, requires an explicit configuration flag, and every
 * use is written to the run report.</p>
 *
 * <p>Requires administrative privilege — the executing account must be a member of
 * Administrators, which is a further reason to keep this off in normal operation.</p>
 */
public final class LifecycleUtil {

    private LifecycleUtil() {
    }

    /**
     * @param stateName internal state name, e.g. {@code INWORK}, {@code RELEASED}
     */
    public static void setState(LifeCycleManaged object, String stateName) throws WTException, LoaderException {
        if (stateName == null || stateName.trim().length() == 0) {
            return;
        }
        State state;
        try {
            state = State.toState(stateName.trim().toUpperCase());
        } catch (WTException e) {
            throw new LoaderException("Invalid lifecycle state '" + stateName
                    + "'. Use the internal state name (e.g. INWORK, UNDERREVIEW, RELEASED), "
                    + "not the display label", e);
        }
        LifeCycleHelper.service.setLifeCycleState(object, state);
    }
}
