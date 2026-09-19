package ext.change;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import wt.change2.ChangeHelper2;
import wt.change2.WTChangeActivity2;
import wt.fc.IdentityFactory;
import wt.fc.Persistable;
import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.lifecycle.LifeCycleHelper;
import wt.lifecycle.LifeCycleManaged;
import wt.lifecycle.State;
import wt.log4j.LogR;
import wt.pom.Transaction;
import wt.session.SessionServerHelper;
import wt.util.WTException;
import wt.util.WTMessage;
import wt.util.WTPropertyVetoException;
import wt.util.WTRuntimeException;
import wt.vc.wip.WorkInProgressHelper;
import wt.vc.wip.Workable;

/**
 * Promotes every RESULTING object of a Change Activity (WTChangeActivity2) to a
 * target lifecycle state -- by default "Under Review" -- and reports the ones
 * that could not get there.
 *
 * <p>Three entry points, because "show the user an error" means different things
 * depending on where the expression is hung in the workflow template:
 *
 * <pre>
 *   // 1. Silent. Logs failures, never interrupts the workflow.
 *   ext.change.ChangeActivityStateUtil.promoteResultingObjects(primaryBusinessObject);
 *
 *   // 2. Loud. Throws with a localized message listing the offending objects.
 *   //    Shows as a red banner ONLY when this runs in the user's own thread,
 *   //    i.e. the Complete expression of a task the user just completed.
 *   ext.change.ChangeActivityStateUtil.promoteResultingObjectsOrFail(primaryBusinessObject);
 *
 *   // 3. Reportable. Returns "" on full success, or a human-readable failure
 *   //    report you drop into a workflow variable and route on. This is the
 *   //    only option that works for asynchronous / robot nodes.
 *   failureReport = ext.change.ChangeActivityStateUtil.promoteAndReport(primaryBusinessObject);
 * </pre>
 *
 * <p>Upgrade risk: LOW. New class in ext.*, called from a workflow expression.
 * Nothing OOTB is overridden.
 */
public final class ChangeActivityStateUtil {

    /** Internal (not display) name of the OOTB "Under Review" lifecycle state. */
    private static final String UNDER_REVIEW = "UNDERREVIEW";

    private static final String RESOURCE = changeResource.class.getName();

    private static final Logger LOGGER = LogR.getLogger(ChangeActivityStateUtil.class.getName());

    /** Utility class -- never instantiated. */
    private ChangeActivityStateUtil() {
    }

    // -----------------------------------------------------------------------
    // Entry point 1 -- silent
    // -----------------------------------------------------------------------

    /**
     * Promotes what it can, logs what it cannot, never interrupts the workflow.
     *
     * @return the number of objects actually promoted.
     */
    public static int promoteResultingObjects(Object pbo) {
        return promote(pbo, UNDER_REVIEW).getPromotedCount();
    }

    /**
     * Same, but the state code comes from the caller instead of being hardcoded.
     * This is the overload to use from a workflow expression when the target
     * state differs per node -- Under Review on submit, Approved on approval --
     * so one method serves the whole template.
     *
     * @param stateCode the INTERNAL state name from wt.lifecycle.StateRB, e.g.
     *                  "UNDERREVIEW". Not the display name "Under Review".
     * @return the number of changeables actually moved to that state.
     */
    public static int promoteResultingObjects(Object pbo, String stateCode) {
        return promote(pbo, stateCode).getPromotedCount();
    }

    // -----------------------------------------------------------------------
    // Entry point 2 -- loud
    // -----------------------------------------------------------------------

    /**
     * Promotes what it can, then throws if anything could not reach the target
     * state. Use this on the Complete expression of a user task so the message
     * lands in the user's error banner.
     *
     * <p>Throws WTRuntimeException rather than a checked exception on purpose:
     * the workflow expression box compiles your code into a generated method,
     * and an unchecked exception needs no throws clause to compile there.
     *
     * @throws WTRuntimeException if one or more resulting objects could not be
     *                            set to the target state.
     */
    public static void promoteResultingObjectsOrFail(Object pbo) {
        promoteResultingObjectsOrFail(pbo, UNDER_REVIEW);
    }

    public static void promoteResultingObjectsOrFail(Object pbo, String targetStateName) {
        PromotionResult result = promote(pbo, targetStateName);
        if (result.hasFailures()) {
            throw new WTRuntimeException(result.buildLocalizedMessage(targetStateName));
        }
    }

    // -----------------------------------------------------------------------
    // Entry point 3 -- reportable
    // -----------------------------------------------------------------------

    /**
     * Promotes what it can and hands back a report instead of throwing.
     *
     * @return an empty String when every resulting object reached the target
     *         state, otherwise a localized, multi-line description of what
     *         failed and why. Assign it to a workflow String variable and
     *         branch on {@code failureReport.length() > 0}.
     */
    public static String promoteAndReport(Object pbo) {
        return promoteAndReport(pbo, UNDER_REVIEW);
    }

    public static String promoteAndReport(Object pbo, String targetStateName) {
        PromotionResult result = promote(pbo, targetStateName);
        return result.hasFailures() ? result.buildLocalizedMessage(targetStateName) : "";
    }

    // -----------------------------------------------------------------------
    // The actual work
    // -----------------------------------------------------------------------

    private static PromotionResult promote(Object pbo, String targetStateName) {

        PromotionResult result = new PromotionResult();

        if (!(pbo instanceof WTChangeActivity2)) {
            LOGGER.warn("PBO is not a WTChangeActivity2 (got "
                    + (pbo == null ? "null" : pbo.getClass().getName())
                    + "). No resulting objects to promote.");
            return result;
        }

        // Level 1 availability check: is the state defined in this installation at all?
        State target = resolveState(targetStateName);
        if (target == null) {
            // A misconfigured state name is a configuration bug, not a data problem.
            // Report it as a failure so somebody is forced to look at it.
            LOGGER.error("Lifecycle state '" + targetStateName + "' is not defined in this system.");
            result.addFailure(targetStateName, localize(changeResource.REASON_STATE_UNDEFINED, null));
            return result;
        }

        WTChangeActivity2 changeActivity = (WTChangeActivity2) pbo;
        result.setChangeActivityId(displayIdentity(changeActivity));

        // The workflow user is usually not the owner of the resulting parts and
        // would be denied MODIFY. Restored in the finally block, always.
        boolean enforced = SessionServerHelper.manager.setAccessEnforced(false);
        try {
            QueryResult results = ChangeHelper2.service.getChangeablesAfter(changeActivity);

            while (results != null && results.hasMoreElements()) {
                Object element = results.nextElement();

                if (!(element instanceof LifeCycleManaged)) {
                    // Not lifecycle managed at all -- not a failure, there is
                    // nothing a user could do about it.
                    LOGGER.debug("Skipping non-lifecycle-managed resulting object: " + element);
                    result.skip();
                    continue;
                }

                LifeCycleManaged lcObject = (LifeCycleManaged) element;

                if (isAlreadyInState(lcObject, target)) {
                    LOGGER.debug("Already in " + target + ": " + identify(lcObject));
                    result.skip();
                    continue;
                }

                if (isCheckedOut(lcObject)) {
                    // This one IS a failure: a user checked it out and a user can
                    // check it back in, so surface it.
                    LOGGER.warn("Checked out, cannot change state: " + identify(lcObject));
                    result.addFailure(displayIdentity(lcObject),
                            localize(changeResource.REASON_CHECKED_OUT, null));
                    continue;
                }

                promoteOne(lcObject, target, targetStateName, result);
            }
        } catch (WTException wte) {
            // Failing to READ the resulting objects is a different class of problem
            // from failing to promote one of them. Nothing was attempted, so report
            // the whole activity as failed.
            LOGGER.error("Could not read resulting objects of " + changeActivity, wte);
            result.addFailure(displayIdentity(changeActivity),
                    localize(changeResource.REASON_OTHER, new Object[] { String.valueOf(wte.getLocalizedMessage()) }));
        } finally {
            SessionServerHelper.manager.setAccessEnforced(enforced);
        }

        LOGGER.info("Change Activity " + identify(changeActivity)
                + " -> state " + target
                + ": promoted=" + result.getPromotedCount()
                + ", skipped=" + result.getSkippedCount()
                + ", failed=" + result.getFailureCount());

        return result;
    }

    /**
     * Promotes a single object inside its own transaction so that one refusal is
     * contained and the sweep continues. Records the outcome on the result.
     */
    private static void promoteOne(LifeCycleManaged lcObject, State target,
                                   String targetStateName, PromotionResult result) {

        Transaction tx = new Transaction();
        try {
            tx.start();

            // Re-read from the database: the resulting-object link can hand back a
            // stale copy if something earlier in the workflow already touched it.
            LifeCycleManaged fresh = (LifeCycleManaged) PersistenceHelper.manager.refresh(lcObject);

            // Level 2 availability check, by attempt. If the object's lifecycle
            // template has no Under Review phase, this is where we find out.
            LifeCycleHelper.service.setLifeCycleState(fresh, target);

            tx.commit();
            tx = null;

            LOGGER.debug("Promoted to " + target + ": " + identify(fresh));
            result.promoted();

        } catch (WTPropertyVetoException veto) {
            // Something actively refused the transition: a lifecycle gate, a state
            // based access rule, or the state not existing in this life cycle.
            LOGGER.warn("Transition to " + target + " vetoed for " + identify(lcObject)
                    + ": " + veto.getMessage());
            result.addFailure(displayIdentity(lcObject),
                    localize(changeResource.REASON_STATE_UNAVAILABLE, new Object[] { targetStateName }));

        } catch (Exception e) {
            LOGGER.warn("Could not set " + target + " on " + identify(lcObject) + ": " + e.getMessage());
            result.addFailure(displayIdentity(lcObject),
                    localize(changeResource.REASON_OTHER, new Object[] { String.valueOf(e.getMessage()) }));

        } finally {
            if (tx != null) {
                tx.rollback();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** @return the State, or null if this installation does not define that name. */
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

    /** Looks up one message from changeResource in the caller's locale. */
    private static String localize(String key, Object[] params) {
        try {
            return new WTMessage(RESOURCE, key, params).getLocalizedMessage();
        } catch (Exception e) {
            // A missing resource entry must never take down the workflow.
            return key;
        }
    }

    /** User-facing identifier, e.g. the part number. Falls back, never throws. */
    private static String displayIdentity(Object object) {
        try {
            if (object instanceof Persistable) {
                return IdentityFactory.getDisplayIdentifier((Persistable) object).getLocalizedMessage();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not build display identity", e);
        }
        return identify(object);
    }

    /** Short, log-safe identifier. Never throws, because logging must never break the workflow. */
    private static String identify(Object object) {
        try {
            return object == null ? "null" : object.toString();
        } catch (Exception e) {
            return object.getClass().getName();
        }
    }

    // -----------------------------------------------------------------------
    // Result carrier
    // -----------------------------------------------------------------------

    /**
     * Counts and failure list from one sweep. Kept as a nested class so the whole
     * customization stays in a single file -- there is no reason to spread three
     * counters and a List across the source tree.
     */
    public static final class PromotionResult {

        private final List failures = new ArrayList();
        private int promotedCount;
        private int skippedCount;
        private String changeActivityId = "";

        void promoted() {
            promotedCount++;
        }

        void skip() {
            skippedCount++;
        }

        void addFailure(String identity, String reason) {
            failures.add(new Failure(identity, reason));
        }

        void setChangeActivityId(String id) {
            changeActivityId = id;
        }

        public int getPromotedCount() {
            return promotedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public int getFailureCount() {
            return failures.size();
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /** Builds the multi-line message shown in the banner or the task instructions. */
        public String buildLocalizedMessage(String targetStateName) {

            StringBuffer detail = new StringBuffer();
            for (int i = 0; i < failures.size(); i++) {
                Failure failure = (Failure) failures.get(i);
                detail.append("  - ").append(failure.identity)
                      .append(" : ").append(failure.reason);
                if (i < failures.size() - 1) {
                    detail.append('\n');
                }
            }

            Object[] params = new Object[] {
                changeActivityId,
                String.valueOf(failures.size()),
                targetStateName,
                detail.toString()
            };

            return localize(changeResource.PROMOTION_FAILED, params);
        }

        private static final class Failure {
            private final String identity;
            private final String reason;

            Failure(String identity, String reason) {
                this.identity = identity;
                this.reason = reason;
            }
        }
    }
}
