package ext.change;

import org.apache.log4j.Logger;

import wt.change2.ChangeHelper2;
import wt.change2.WTChangeActivity2;
import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.lifecycle.LifeCycleHelper;
import wt.lifecycle.LifeCycleManaged;
import wt.lifecycle.State;
import wt.log4j.LogR;
import wt.pom.Transaction;
import wt.session.SessionServerHelper;
import wt.util.WTException;
import wt.vc.wip.WorkInProgressHelper;
import wt.vc.wip.Workable;

/**
 * Promotes every RESULTING object of a Change Activity (WTChangeActivity2) to a
 * target lifecycle state -- by default "Under Review".
 *
 * <p>Intended to be called from a single-line workflow expression inside the
 * Change Activity workflow template, e.g.:
 *
 * <pre>
 *   ext.change.ChangeActivityStateUtil.promoteResultingObjects(primaryBusinessObject);
 * </pre>
 *
 * <p>Design decisions worth knowing before you copy this:
 * <ul>
 *   <li><b>Best effort, not atomic.</b> Each object is promoted in its own
 *       transaction. One object that cannot reach the target state does not
 *       stop the rest. See {@link #promoteResultingObjects(Object)} for the
 *       atomic alternative.</li>
 *   <li><b>"If the state is available" is enforced at two levels.</b> First the
 *       state name must exist in the system at all (StateRB). Second, the
 *       individual object's lifecycle template must allow it -- an object whose
 *       lifecycle has no Under Review phase is skipped and logged, not failed.</li>
 *   <li><b>Access enforcement is disabled for the promotion only.</b> The
 *       workflow user is frequently not the owner of the resulting parts.</li>
 *   <li><b>Upgrade risk: LOW.</b> Nothing here overrides an OOTB class. It is a
 *       new class in the ext.* package called from a workflow expression, which
 *       is a supported extension point.</li>
 * </ul>
 */
public final class ChangeActivityStateUtil {

    /** Internal (not display) name of the OOTB "Under Review" lifecycle state. */
    private static final String UNDER_REVIEW = "UNDERREVIEW";

    private static final Logger LOGGER = LogR.getLogger(ChangeActivityStateUtil.class.getName());

    /** Utility class -- never instantiated. */
    private ChangeActivityStateUtil() {
    }

    /**
     * Promotes all resulting objects of the given Change Activity to Under Review.
     *
     * @param pbo the workflow primary business object; expected to be a
     *            WTChangeActivity2. Anything else is logged and ignored.
     * @return the number of objects actually promoted.
     */
    public static int promoteResultingObjects(Object pbo) {
        return promoteResultingObjects(pbo, UNDER_REVIEW);
    }

    /**
     * Promotes all resulting objects of the given Change Activity to the named state.
     *
     * @param pbo               the workflow primary business object.
     * @param targetStateName   internal name of the target state, e.g. "UNDERREVIEW".
     * @return the number of objects actually promoted.
     */
    public static int promoteResultingObjects(Object pbo, String targetStateName) {

        if (!(pbo instanceof WTChangeActivity2)) {
            LOGGER.warn("PBO is not a WTChangeActivity2 (got "
                    + (pbo == null ? "null" : pbo.getClass().getName())
                    + "). No resulting objects to promote.");
            return 0;
        }

        // Level 1 availability check: is this state defined in this installation at all?
        State target = resolveState(targetStateName);
        if (target == null) {
            LOGGER.warn("Lifecycle state '" + targetStateName
                    + "' is not defined in this system. Nothing promoted.");
            return 0;
        }

        WTChangeActivity2 changeActivity = (WTChangeActivity2) pbo;

        int promoted = 0;
        int skipped = 0;
        int failed = 0;

        // Access enforcement is turned off for the whole sweep: the workflow user
        // is usually not the owner of the resulting parts and would be denied MODIFY.
        boolean enforced = SessionServerHelper.manager.setAccessEnforced(false);
        try {
            QueryResult results = ChangeHelper2.service.getChangeablesAfter(changeActivity);

            while (results != null && results.hasMoreElements()) {
                Object element = results.nextElement();

                if (!(element instanceof LifeCycleManaged)) {
                    LOGGER.debug("Skipping non-lifecycle-managed resulting object: " + element);
                    skipped++;
                    continue;
                }

                LifeCycleManaged lcObject = (LifeCycleManaged) element;

                if (isAlreadyInState(lcObject, target)) {
                    LOGGER.debug("Already in " + target + ": " + identify(lcObject));
                    skipped++;
                    continue;
                }

                if (isCheckedOut(lcObject)) {
                    LOGGER.warn("Checked out, cannot change state: " + identify(lcObject));
                    skipped++;
                    continue;
                }

                if (promoteOne(lcObject, target)) {
                    promoted++;
                } else {
                    failed++;
                }
            }
        } catch (WTException wte) {
            // Failure to READ the resulting objects is different from failure to
            // promote one of them -- this one is fatal to the whole expression.
            LOGGER.error("Could not read resulting objects of " + changeActivity, wte);
        } finally {
            SessionServerHelper.manager.setAccessEnforced(enforced);
        }

        LOGGER.info("Change Activity " + identify(changeActivity)
                + " -> state " + target
                + ": promoted=" + promoted + ", skipped=" + skipped + ", failed=" + failed);

        return promoted;
    }

    /**
     * Promotes a single object inside its own transaction so that a failure is
     * contained and the sweep can continue.
     *
     * @return true if the object was persisted in the target state.
     */
    private static boolean promoteOne(LifeCycleManaged lcObject, State target) {

        Transaction tx = new Transaction();
        try {
            tx.start();

            // Re-read from the database: the resulting-object link may hand back a
            // stale copy if something earlier in the workflow already touched it.
            LifeCycleManaged fresh = (LifeCycleManaged) PersistenceHelper.manager.refresh(lcObject);

            // Level 2 availability check, by attempt: if the object's lifecycle
            // template has no Under Review phase, this throws and we treat the
            // object as "state not available" rather than as a hard error.
            LifeCycleHelper.service.setLifeCycleState(fresh, target);

            tx.commit();
            tx = null;

            LOGGER.debug("Promoted to " + target + ": " + identify(fresh));
            return true;

        } catch (Exception e) {
            LOGGER.warn("State '" + target + "' not available (or refused) for "
                    + identify(lcObject) + " -- skipping. Reason: " + e.getMessage());
            return false;
        } finally {
            if (tx != null) {
                tx.rollback();
            }
        }
    }

    /**
     * Turns the internal state name into a State enumeration value.
     *
     * @return the State, or null if this installation does not define that name.
     */
    private static State resolveState(String stateName) {
        try {
            return State.toState(stateName);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAlreadyInState(LifeCycleManaged lcObject, State target) {
        State current = lcObject.getLifeCycleState();
        return current != null && current.equals(target);
    }

    private static boolean isCheckedOut(LifeCycleManaged lcObject) {
        if (!(lcObject instanceof Workable)) {
            return false;
        }
        return WorkInProgressHelper.isCheckedOut((Workable) lcObject);
    }

    /** Short, log-safe identifier. Never throws, because logging must never break the workflow. */
    private static String identify(Object object) {
        try {
            return object == null ? "null" : object.toString();
        } catch (Exception e) {
            return object.getClass().getName();
        }
    }
}
