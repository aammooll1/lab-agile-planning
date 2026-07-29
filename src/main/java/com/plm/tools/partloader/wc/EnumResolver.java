package com.plm.tools.partloader.wc;

import com.plm.tools.partloader.util.LoaderException;

import wt.part.PartType;
import wt.part.Quantity;
import wt.part.Source;
import wt.part.TraceCode;
import wt.util.WTException;

/**
 * Single place where the loader converts CSV strings into Windchill enumerated types.
 *
 * <p>Isolated on purpose. These four enumerations are the part of the WTPart API most
 * likely to differ between installations and releases: their internal values are defined in
 * {@code wt/part/partResource.rbInfo} (and its localised siblings) and are routinely
 * extended by customers — an automotive Tier-1 typically adds source values such as
 * "customer supplied" or "free issue" to model consignment stock. Keeping the conversions
 * here means a data-model change touches one file.</p>
 *
 * <p><b>Always pass internal values, never display values.</b> The internal value for
 * "Make" is {@code make}; the UI may show it translated. Load files that contain display
 * values work in English DEV and fail in a localised production environment.</p>
 */
public final class EnumResolver {

    private EnumResolver() {
    }

    public static Source toSource(String value) throws LoaderException {
        if (isEmpty(value)) {
            return null;
        }
        try {
            return Source.toSource(value.trim().toLowerCase());
        } catch (WTException e) {
            throw new LoaderException("Invalid source '" + value
                    + "'. Expected an internal value from wt.part.Source (OOTB: make, buy)", e);
        }
    }

    /**
     * Assembly mode. The UI label on the part create wizard is "Assembly Mode"; the
     * underlying attribute is {@code partType} and the enumeration is {@code wt.part.PartType}.
     * OOTB internal values are {@code separable}, {@code inseparable} and {@code component}.
     */
    public static PartType toPartType(String value) throws LoaderException {
        if (isEmpty(value)) {
            return null;
        }
        try {
            return PartType.toPartType(value.trim().toLowerCase());
        } catch (WTException e) {
            throw new LoaderException("Invalid assembly mode / part type '" + value
                    + "'. Expected an internal value from wt.part.PartType "
                    + "(OOTB: separable, inseparable, component)", e);
        }
    }

    /**
     * Default unit of measure. Note that the internal values are short codes
     * ({@code ea}, {@code kg}, {@code m}, {@code l}), not the display labels
     * ("each", "kilogram"). Getting this wrong is the single most common cause of a
     * loader run failing after the first few hundred rows, because the first rows in a
     * file are usually the simple "ea" ones.
     */
    public static Quantity.QuantityUnit toUnit(String value) throws LoaderException {
        if (isEmpty(value)) {
            return null;
        }
        try {
            return Quantity.QuantityUnit.toQuantityUnit(value.trim().toLowerCase());
        } catch (WTException e) {
            throw new LoaderException("Invalid unit of measure '" + value
                    + "'. Expected an internal value from wt.part.Quantity.QuantityUnit "
                    + "(OOTB: ea, mm, cm, m, in, ft, mg, g, kg, oz, lb, ml, l, ...)", e);
        }
    }

    /**
     * Default trace code — how individual instances of the part are tracked in
     * manufacturing. Relevant in automotive wherever serialisation or lot traceability is a
     * customer or homologation requirement, and normally derived from the component's safety
     * classification rather than chosen ad hoc. OOTB internal values are {@code untraced},
     * {@code lot} and {@code serialNumbered}.
     */
    public static TraceCode toTraceCode(String value) throws LoaderException {
        if (isEmpty(value)) {
            return null;
        }
        try {
            return TraceCode.toTraceCode(value.trim());
        } catch (WTException e) {
            throw new LoaderException("Invalid trace code '" + value
                    + "'. Expected an internal value from wt.part.TraceCode "
                    + "(OOTB: untraced, lot, serialNumbered)", e);
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }
}
