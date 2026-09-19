package ext.change;

import wt.util.resource.RBComment;
import wt.util.resource.RBEntry;
import wt.util.resource.RBUUID;
import wt.util.resource.WTListResourceBundle;

/**
 * Localizable messages for the Change Activity state promotion.
 *
 * <p>Windchill 10.x and later use annotation-driven resource bundles: the
 * message text lives in the @RBEntry annotation, the constant value is the
 * lookup key, and the build generates the .class that WTMessage reads. There
 * is no .rbInfo file any more.
 *
 * <p>Two rules that catch everyone the first time:
 * <ul>
 *   <li>{0}, {1}, ... are java.text.MessageFormat placeholders, substituted in
 *       the order the Object[] is passed.</li>
 *   <li>A literal apostrophe must be DOUBLED ('' not ') or MessageFormat eats
 *       it and silently stops substituting the rest of the line. The text below
 *       avoids apostrophes entirely rather than relying on you to remember.</li>
 * </ul>
 *
 * <p>Site administrators can override any of these without a rebuild via
 * Site &gt; Utilities &gt; Resource Customization.
 */
@RBUUID("ext.change.changeResource")
public final class changeResource extends WTListResourceBundle {

    @RBEntry("Change Activity {0}: {1} resulting object(s) could not be set to the \"{2}\" state.\n\n{3}\n\nCorrect the objects listed above, then complete this task again.")
    @RBComment("Shown to the user when one or more resulting objects cannot be promoted. "
             + "{0}=change activity number, {1}=failure count, {2}=target state, {3}=indented per-object detail list.")
    public static final String PROMOTION_FAILED = "PROMOTION_FAILED";

    @RBEntry("the \"{0}\" state is not available in the life cycle of this object")
    @RBComment("Reason text for one object. {0}=target state internal name.")
    public static final String REASON_STATE_UNAVAILABLE = "REASON_STATE_UNAVAILABLE";

    @RBEntry("the object is checked out and cannot change state")
    @RBComment("Reason text for one object that is currently checked out.")
    public static final String REASON_CHECKED_OUT = "REASON_CHECKED_OUT";

    @RBEntry("the target state is not defined in this system")
    @RBComment("Configuration error: the state internal name does not exist in wt.lifecycle.StateRB.")
    public static final String REASON_STATE_UNDEFINED = "REASON_STATE_UNDEFINED";

    @RBEntry("{0}")
    @RBComment("Fallback reason: passes through the underlying exception message. {0}=exception message.")
    public static final String REASON_OTHER = "REASON_OTHER";
}
