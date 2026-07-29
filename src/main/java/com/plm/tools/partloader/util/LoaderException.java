package com.plm.tools.partloader.util;

/**
 * Raised for input, configuration and validation problems that are the loader's own
 * business, as opposed to {@code wt.util.WTException} which signals a Windchill failure.
 *
 * <p>Keeping the two separate matters operationally: a LoaderException is a data problem
 * the business owner must fix in the source file, a WTException is a platform problem the
 * PLM support team must investigate.</p>
 */
public class LoaderException extends Exception {

    private static final long serialVersionUID = 1L;

    public LoaderException(String message) {
        super(message);
    }

    public LoaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
