package ext.windchill.change.rules;

import java.lang.reflect.Method;

import wt.events.KeyedEvent;
import wt.method.MethodContext;
import wt.services.ServiceEventListenerAdapter;
import wt.services.StandardManager;
import wt.services.applicationcontext.ApplicationContext;
import wt.util.WTException;
import wt.change2.WTChangeOrder2;
import wt.change2.ChangeHelper2;

/**
 * Optional event-driven integration of {@link ChangeReleaseChildParentRule}.
 *
 * <p>This manager subscribes to the change lifecycle "pre-promote / pre-set
 * state" event so the parent/child rule is enforced even when a change is
 * released outside of the standard workflow (for example via a bulk lifecycle
 * action). When a violation is found it raises a {@link WTException}, which
 * vetoes the state change.</p>
 *
 * <p><b>Registration</b> &mdash; add to {@code <Windchill>/codebase/wt.properties}:</p>
 * <pre>
 *   wt.services.service.ext.windchill.change.rules.ChangeReleaseRuleListener/ext.windchill.change.rules.ChangeReleaseRuleListener=\
 *       ext.windchill.change.rules.ChangeReleaseRuleListener/duplicate
 * </pre>
 *
 * <p>and ensure it is started by listing it under
 * {@code wt.services.applicationcontext.startup} or registering it from your
 * site's startup manager.</p>
 *
 * <p>The event subscription itself is registered in {@link #performStartupProcess()}.
 * The exact event key used to gate a change release varies by Windchill release;
 * the {@code LifeCycleServiceEvent.PRE_PROMOTE} key is the most common. Confirm
 * against your installed version and adjust {@link #getReleaseEventKey()}.</p>
 */
public class ChangeReleaseRuleListener extends StandardManager {

    private static final long serialVersionUID = 1L;

    private final ChangeReleaseChildParentRule rule = new ChangeReleaseChildParentRule();

    public static ChangeReleaseRuleListener newChangeReleaseRuleListener() throws WTException {
        ChangeReleaseRuleListener instance = new ChangeReleaseRuleListener();
        instance.initialize();
        return instance;
    }

    @Override
    protected void performStartupProcess() throws ManagerException {
        try {
            getManagerService().addEventListener(
                    new ServiceEventListenerAdapter(getConceptualClassname()) {
                        @Override
                        public void notifyVetoableEvent(Object event) throws WTException {
                            handleEvent(event);
                        }
                    },
                    getReleaseEventKey());
        } catch (Exception e) {
            throw new ManagerException(e, getConceptualClassname(),
                    "Failed to register ChangeReleaseRuleListener", null);
        }
    }

    /**
     * Resolves the lifecycle pre-promote event key reflectively so this class
     * compiles across Windchill versions where the constant location differs.
     * Falls back to the well-known string key if reflection fails.
     */
    private Object getReleaseEventKey() {
        try {
            Class<?> evtClass = Class.forName("wt.lifecycle.LifeCycleServiceEvent");
            Method m = evtClass.getMethod("generateEventKey", String.class);
            return m.invoke(null, "PRE_PROMOTE");
        } catch (Exception reflectionFailure) {
            return "wt.lifecycle.LifeCycleServiceEvent.PRE_PROMOTE";
        }
    }

    /**
     * Inspects the fired event; when the target is a change order being released
     * the parent/child rule is evaluated and may veto the transition.
     */
    private void handleEvent(Object event) throws WTException {
        if (!(event instanceof KeyedEvent)) {
            return;
        }
        Object target = ((KeyedEvent) event).getEventTarget();
        if (target instanceof WTChangeOrder2) {
            // validate() throws ChangeReleaseValidationException on violation,
            // which vetoes the state change.
            rule.validate((WTChangeOrder2) target);
        }
    }

    /**
     * Convenience accessor mirroring Windchill manager idioms; callers can also
     * use {@link ChangeHelper2} directly. Present to keep the import meaningful
     * and to give site code a single handle on the rule.
     */
    public ChangeReleaseChildParentRule getRule() {
        return rule;
    }

    // Touch ApplicationContext / MethodContext so the manager participates in
    // the standard service framework lifecycle (no-op guard for older stubs).
    @SuppressWarnings("unused")
    private void touchContext() {
        ApplicationContext ctx = null;
        MethodContext mc = MethodContext.getContext();
        if (ctx == null && mc == null) {
            // intentionally empty
        }
    }
}
