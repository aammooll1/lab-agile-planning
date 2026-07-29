package com.plm.tools.partloader.wc;

import java.util.Map;

import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import com.ptc.core.lwc.server.PersistableAdapter;
import com.ptc.core.meta.common.UpdateOperationIdentifier;

import wt.fc.Persistable;
import wt.session.SessionHelper;
import wt.util.WTException;

/**
 * Writes soft attributes (IBAs) onto a persistable using the logical attribute framework.
 *
 * <p>Two approaches exist in Windchill. The legacy one manipulates
 * {@code wt.iba.value.DefaultAttributeContainer} and the {@code IBAValueDBService}
 * directly; it still compiles, and it is the wrong choice. It bypasses the logical
 * attribute layer, so constraints, calculated attributes, unit-of-measure handling and
 * type-level defaults are all skipped, and values written that way can disagree with what
 * the UI displays. {@link PersistableAdapter} is the supported path and is what the UI
 * itself uses.</p>
 *
 * <p>Ordering matters: the object must be persisted before its IBAs are written, because
 * the adapter resolves the attribute definitions from the object's actual type
 * identifier.</p>
 */
public final class IbaWriter {

    private IbaWriter() {
    }

    /**
     * Applies all supplied attribute values. Blank values are never written — see the
     * comment in RowMapper about partial re-loads.
     *
     * @param target    a persisted object
     * @param values    internal attribute name to string value
     * @param strict    when true an unknown attribute fails the row; when false it is logged
     *                  and skipped
     * @return number of attributes actually applied
     */
    public static int apply(Persistable target, Map<String, String> values, boolean strict)
            throws WTException, LoaderException {
        if (values == null || values.isEmpty()) {
            return 0;
        }

        PersistableAdapter adapter = new PersistableAdapter(
                target, null, SessionHelper.getLocale(), new UpdateOperationIdentifier());

        int applied = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            try {
                // load() must be called per attribute before set(); it pulls the attribute
                // definition and current value into the adapter's container.
                adapter.load(name);
                adapter.set(name, value);
                applied++;
            } catch (Exception e) {
                String msg = "Attribute '" + name + "' could not be set to '" + value + "': "
                        + LoaderLog.describe(e);
                if (strict) {
                    throw new LoaderException(msg, e);
                }
                LoaderLog.warn(msg + " (skipped, loader.iba.strict=false)");
            }
        }

        if (applied > 0) {
            adapter.apply();
        }
        return applied;
    }

    /**
     * Validates that the named attributes exist for the given type, without persisting
     * anything. Used by dry-run so that a data-model mismatch is reported before the
     * production window rather than during it.
     *
     * @param typeIdentifier e.g. {@code WCTYPE|wt.part.WTPart|com.acme.PurchasedPart}
     * @return null when every attribute resolves, otherwise a human readable problem list
     */
    public static String validate(String typeIdentifier, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder problems = new StringBuilder();
        try {
            PersistableAdapter adapter = new PersistableAdapter(
                    typeIdentifier, null, SessionHelper.getLocale(), new UpdateOperationIdentifier());
            for (String name : values.keySet()) {
                try {
                    adapter.load(name);
                } catch (Exception e) {
                    if (problems.length() > 0) {
                        problems.append("; ");
                    }
                    problems.append("unknown attribute '").append(name).append('\'');
                }
            }
        } catch (Throwable t) {
            // Never let dry-run validation fail the run itself. If the type-identifier
            // constructor is unavailable in this release, report it and continue: the real
            // apply() path is still fully validated at commit time.
            return "IBA pre-validation unavailable in this environment (" + LoaderLog.describe(t)
                    + ") - attributes will be validated at commit time";
        }
        return problems.length() == 0 ? null : problems.toString();
    }
}
