package ext.windchill.change.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Central, easily tunable configuration for the change-release parent/child
 * business rule.
 *
 * <p>The values here are intentionally kept as simple constants so the rule can
 * be deployed without a dependency on a properties service. If you prefer to
 * drive these from {@code wt.properties} or a {@code *.rbInfo} resource bundle,
 * replace the static initialisers with a {@code WTProperties} lookup.</p>
 */
public final class ChangeReleaseRuleConstants {

    private ChangeReleaseRuleConstants() {
        // utility class - no instances
    }

    /**
     * Lifecycle state names that are considered "released" for the purposes of
     * this rule. A child part whose current state is in this set is treated as
     * satisfying the parent's release requirement.
     *
     * <p>Adjust this to match your site's lifecycle template (for example some
     * sites use {@code APPROVED} or a custom {@code PRODUCTION} state).</p>
     */
    public static final Set<String> RELEASED_STATE_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "RELEASED",
                    "PRODUCTION",
                    "APPROVED"
            )));

    /**
     * When {@code true}, a child that is itself one of the objects being
     * released by the <em>same</em> change is accepted, even if its current
     * (pre-release) state is not yet released. This is normally what you want:
     * a change that releases both a parent and its children in one shot should
     * be allowed.
     */
    public static final boolean ALLOW_CHILD_IN_SAME_CHANGE = true;

    /**
     * When {@code true}, the rule walks the full multi-level BOM. When
     * {@code false}, only the immediate (single-level) children of each released
     * parent are validated.
     */
    public static final boolean VALIDATE_FULL_BOM = false;

    /**
     * Defensive guard against malformed / cyclic BOM data when
     * {@link #VALIDATE_FULL_BOM} is enabled.
     */
    public static final int MAX_BOM_DEPTH = 50;
}
