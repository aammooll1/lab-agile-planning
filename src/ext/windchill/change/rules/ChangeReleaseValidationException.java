package ext.windchill.change.rules;

import wt.util.WTException;

/**
 * Thrown when a change cannot be released because one or more parent parts
 * have child components that are not in a released state.
 *
 * <p>The message is built up by {@link ChangeReleaseChildParentRule} and is
 * suitable for surfacing directly to the end user (for example as the failure
 * reason of a workflow expression robot or a lifecycle pre-condition).</p>
 */
public class ChangeReleaseValidationException extends WTException {

    private static final long serialVersionUID = 1L;

    public ChangeReleaseValidationException(String message) {
        super(message);
    }

    public ChangeReleaseValidationException(String message, Throwable cause) {
        super(cause, message);
    }
}
