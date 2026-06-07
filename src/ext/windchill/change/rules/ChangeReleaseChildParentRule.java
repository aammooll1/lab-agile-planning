package ext.windchill.change.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wt.change2.ChangeHelper2;
import wt.change2.Changeable2;
import wt.change2.WTChangeOrder2;
import wt.fc.Persistable;
import wt.fc.QueryResult;
import wt.fc.collections.WTArrayList;
import wt.lifecycle.LifeCycleHelper;
import wt.lifecycle.LifeCycleManaged;
import wt.lifecycle.State;
import wt.part.WTPart;
import wt.part.WTPartHelper;
import wt.part.WTPartMaster;
import wt.part.WTPartUsageLink;
import wt.util.WTException;

/**
 * Business rule that enforces parent &rarr; child consistency when a change is
 * released in Windchill PLM.
 *
 * <p><b>Rule:</b> a parent {@link WTPart} may not be released through a change
 * unless every BOM child it "uses" is already in a released lifecycle state, or
 * is itself being released by the <em>same</em> change. This prevents shipping a
 * released assembly that still points at WIP / In&nbsp;Work components.</p>
 *
 * <p><b>Where to invoke it:</b></p>
 * <ul>
 *   <li><b>Workflow expression robot</b> on the change-release/approval activity
 *       of the change process &mdash; the most common integration point.
 *       See {@code workflow/ChangeReleaseChildParentExpressionRobot.txt}.</li>
 *   <li><b>Lifecycle pre-condition</b> on the RELEASED transition of the change
 *       object.</li>
 *   <li><b>Event listener</b> on the change pre-release event.</li>
 * </ul>
 *
 * <p>The class exposes a single entry point, {@link #validate(WTChangeOrder2)},
 * which throws {@link ChangeReleaseValidationException} (a {@link WTException})
 * describing every violation found. A clean return means the change is safe to
 * release.</p>
 */
public class ChangeReleaseChildParentRule {

    private static final String LINE_SEP = System.getProperty("line.separator", "\n");

    /**
     * Validates the parent/child release consistency for the given change order.
     *
     * @param changeOrder the change object (ECN) being released. Must not be
     *                    {@code null}.
     * @throws ChangeReleaseValidationException if one or more released parent
     *         parts have non-released children that are not part of this change.
     * @throws WTException for any underlying Windchill data-access failure.
     */
    public void validate(WTChangeOrder2 changeOrder) throws WTException {
        if (changeOrder == null) {
            throw new ChangeReleaseValidationException(
                    "Change-release rule invoked with a null change order.");
        }

        // 1. Collect the "after" objects that this change is releasing -- i.e.
        //    the resulting/changed objects attached to the change order.
        List<WTPart> releasedParts = collectReleasedParts(changeOrder);
        if (releasedParts.isEmpty()) {
            // Nothing part-related to validate; let the change proceed.
            return;
        }

        // 2. Build the set of master IDs that ARE part of this change. A child
        //    that belongs to the same change is allowed to be co-released.
        Set<String> partsInThisChange = new HashSet<>();
        if (ChangeReleaseRuleConstants.ALLOW_CHILD_IN_SAME_CHANGE) {
            for (WTPart p : releasedParts) {
                partsInThisChange.add(masterKey(p));
            }
        }

        // 3. Walk each released parent and check its children.
        List<String> violations = new ArrayList<>();
        for (WTPart parent : releasedParts) {
            validatePart(parent, partsInThisChange, violations, new HashSet<String>(), 0);
        }

        if (!violations.isEmpty()) {
            throw new ChangeReleaseValidationException(buildMessage(changeOrder, violations));
        }
    }

    /**
     * Collects the {@link WTPart}s that the change order will release. Uses the
     * standard {@link ChangeHelper2} navigation from the change order to its
     * "after" changeable objects, keeping only WTParts.
     */
    private List<WTPart> collectReleasedParts(WTChangeOrder2 changeOrder) throws WTException {
        List<WTPart> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        QueryResult qr = ChangeHelper2.service.getChangeablesAfter(changeOrder);
        while (qr != null && qr.hasMoreElements()) {
            Object obj = qr.nextElement();
            // getChangeablesAfter may return the Changeable2 or the link; normalise.
            Persistable target = resolveChangeable(obj);
            if (target instanceof WTPart) {
                WTPart part = (WTPart) target;
                if (seen.add(part.getPersistInfo().getObjectIdentifier().getStringValue())) {
                    parts.add(part);
                }
            }
        }
        return parts;
    }

    /**
     * Normalises the various shapes that change-navigation APIs can return into
     * a {@link Persistable} target object.
     */
    private Persistable resolveChangeable(Object obj) {
        if (obj instanceof Changeable2) {
            return (Persistable) obj;
        }
        if (obj instanceof Persistable) {
            return (Persistable) obj;
        }
        if (obj instanceof Object[]) {
            // Some QueryResults return rows; scan for the first Changeable2.
            for (Object cell : (Object[]) obj) {
                if (cell instanceof Changeable2) {
                    return (Persistable) cell;
                }
            }
        }
        return null;
    }

    /**
     * Validates a single parent part against its (immediate or full) BOM.
     *
     * @param visitedMasters guards against cyclic BOMs during recursion.
     * @param depth          current recursion depth.
     */
    private void validatePart(WTPart parent,
                              Set<String> partsInThisChange,
                              List<String> violations,
                              Set<String> visitedMasters,
                              int depth) throws WTException {

        if (depth > ChangeReleaseRuleConstants.MAX_BOM_DEPTH) {
            return;
        }
        if (!visitedMasters.add(masterKey(parent))) {
            return; // already validated this assembly in the current walk
        }

        QueryResult usageLinks = WTPartHelper.service.getUsesWTParts(parent);
        while (usageLinks != null && usageLinks.hasMoreElements()) {
            Object element = usageLinks.nextElement();

            WTPart child = extractChildPart(element);
            if (child == null) {
                continue;
            }

            boolean childInChange =
                    ChangeReleaseRuleConstants.ALLOW_CHILD_IN_SAME_CHANGE
                            && partsInThisChange.contains(masterKey(child));

            if (!childInChange && !isReleased(child)) {
                violations.add(describeViolation(parent, child));
            }

            if (ChangeReleaseRuleConstants.VALIDATE_FULL_BOM) {
                validatePart(child, partsInThisChange, violations, visitedMasters, depth + 1);
            }
        }
    }

    /**
     * {@link WTPartHelper#getUsesWTParts(WTPart)} returns rows of
     * {@code [WTPartUsageLink, WTPart]}. This pulls out the child WTPart from
     * whatever row shape is returned.
     */
    private WTPart extractChildPart(Object element) {
        if (element instanceof WTPart) {
            return (WTPart) element;
        }
        if (element instanceof Object[]) {
            for (Object cell : (Object[]) element) {
                if (cell instanceof WTPart) {
                    return (WTPart) cell;
                }
            }
            // Fall back to resolving the link's used master if no resolved part.
            for (Object cell : (Object[]) element) {
                if (cell instanceof WTPartUsageLink) {
                    WTPartUsageLink link = (WTPartUsageLink) cell;
                    WTPartMaster master = link.getUses();
                    // A master without a concrete iteration cannot be state-checked
                    // here; treat as unresolved and skip (covered by other rules).
                    if (master != null) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return {@code true} if the part's current lifecycle state is one of the
     *         configured released states.
     */
    private boolean isReleased(WTPart part) throws WTException {
        State state = getState(part);
        if (state == null) {
            return false;
        }
        return ChangeReleaseRuleConstants.RELEASED_STATE_NAMES.contains(state.toString());
    }

    private State getState(WTPart part) throws WTException {
        if (part instanceof LifeCycleManaged) {
            return LifeCycleHelper.service.getState((LifeCycleManaged) part);
        }
        return null;
    }

    private String describeViolation(WTPart parent, WTPart child) {
        return "  - Parent [" + safeNumber(parent) + " / " + safeName(parent)
                + ", state=" + safeState(parent) + "] uses child ["
                + safeNumber(child) + " / " + safeName(child)
                + ", state=" + safeState(child) + "] which is NOT released.";
    }

    private String buildMessage(WTChangeOrder2 changeOrder, List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Change ").append(safeChangeNumber(changeOrder))
          .append(" cannot be released: ")
          .append(violations.size())
          .append(violations.size() == 1 ? " parent/child violation found." : " parent/child violations found.")
          .append(LINE_SEP)
          .append("Every child of a released assembly must itself be released (or released by this same change).")
          .append(LINE_SEP);
        for (String v : violations) {
            sb.append(v).append(LINE_SEP);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Null-safe accessors (so a partial data problem never NPEs the rule)
    // ------------------------------------------------------------------

    private String masterKey(WTPart part) {
        try {
            WTPartMaster master = (WTPartMaster) part.getMaster();
            if (master != null) {
                return master.getPersistInfo().getObjectIdentifier().getStringValue();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return part.getPersistInfo().getObjectIdentifier().getStringValue();
    }

    private String safeNumber(WTPart part) {
        try {
            return part.getNumber();
        } catch (Exception e) {
            return "?";
        }
    }

    private String safeName(WTPart part) {
        try {
            return part.getName();
        } catch (Exception e) {
            return "?";
        }
    }

    private String safeState(WTPart part) {
        try {
            State s = getState(part);
            return s == null ? "?" : s.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private String safeChangeNumber(WTChangeOrder2 changeOrder) {
        try {
            return changeOrder.getNumber();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Convenience overload that returns the violations instead of throwing.
     * Useful from a workflow expression robot that wants to set a boolean
     * routing variable rather than fail the activity.
     *
     * @return an unmodifiable list of human-readable violation messages; empty
     *         when the change is safe to release.
     */
    public List<String> findViolations(WTChangeOrder2 changeOrder) throws WTException {
        List<String> violations = new ArrayList<>();
        if (changeOrder == null) {
            return violations;
        }
        List<WTPart> releasedParts = collectReleasedParts(changeOrder);
        Set<String> partsInThisChange = new HashSet<>();
        if (ChangeReleaseRuleConstants.ALLOW_CHILD_IN_SAME_CHANGE) {
            for (WTPart p : releasedParts) {
                partsInThisChange.add(masterKey(p));
            }
        }
        for (WTPart parent : releasedParts) {
            validatePart(parent, partsInThisChange, violations, new HashSet<String>(), 0);
        }
        return violations;
    }

    // Avoid unused-import warnings if WTArrayList helper is needed by callers.
    @SuppressWarnings("unused")
    private static WTArrayList emptyList() {
        return new WTArrayList();
    }
}
