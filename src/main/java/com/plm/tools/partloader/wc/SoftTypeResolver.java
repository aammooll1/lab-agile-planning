package com.plm.tools.partloader.wc;

import java.util.HashMap;
import java.util.Map;

import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import com.ptc.core.meta.common.TypeIdentifier;
import com.ptc.core.meta.common.TypeIdentifierHelper;
import com.ptc.core.meta.type.common.TypeDefinitionReference;
import com.ptc.core.meta.type.common.TypeIdentifierUtilityHelper;

import wt.part.WTPart;
import wt.util.WTException;

/**
 * Assigns a soft type (subtype) to a newly created part.
 *
 * <p>Practically every real implementation subtypes WTPart — Purchased Part, Manufactured
 * Part, Standard Part, Customer Part — because the subtype is what drives the OIR numbering
 * rules, the attribute set and the access policy. A loader that can only create plain
 * {@code wt.part.WTPart} objects will therefore produce parts that are numbered by the wrong
 * rule and are missing their mandatory attributes, and the cleanup is a re-load.</p>
 *
 * <p>The type identifier format is
 * {@code WCTYPE|wt.part.WTPart|<org internal name>.<subtype internal name>}, for example
 * {@code WCTYPE|wt.part.WTPart|com.acme.PurchasedPart}. The load file may supply either the
 * full identifier or just the trailing subtype name, which is then prefixed automatically.</p>
 *
 * <p><b>Verify on first compile.</b> The type-management API is the least stable part of the
 * Windchill SDK across releases. See docs/API_VERIFICATION.md item 1.</p>
 */
public final class SoftTypeResolver {

    private static final String WTPART_ROOT = "WCTYPE|wt.part.WTPart|";

    private final Map<String, TypeDefinitionReference> cache = new HashMap<String, TypeDefinitionReference>();

    /** Normalises a load-file value into a full Windchill type identifier string. */
    public static String toTypeIdentifierString(String softType) {
        if (softType == null || softType.trim().length() == 0) {
            return "WCTYPE|wt.part.WTPart";
        }
        String v = softType.trim();
        return v.startsWith("WCTYPE|") ? v : WTPART_ROOT + v;
    }

    /**
     * Applies the soft type to a part that has not yet been stored.
     *
     * @param softType value from the load file; null or blank leaves the part as a plain WTPart
     */
    public void apply(WTPart part, String softType) throws WTException, LoaderException {
        if (softType == null || softType.trim().length() == 0) {
            return;
        }
        String typeId = toTypeIdentifierString(softType);
        TypeDefinitionReference reference = cache.get(typeId);
        if (reference == null) {
            try {
                TypeIdentifier identifier = TypeIdentifierHelper.getTypeIdentifier(typeId);
                reference = TypeIdentifierUtilityHelper.service.getTypeDefinitionReference(identifier);
            } catch (Exception e) {
                throw new LoaderException("Soft type '" + typeId + "' could not be resolved. Check the internal "
                        + "name in Type and Attribute Management (it is case sensitive and includes the "
                        + "organisation prefix). Cause: " + LoaderLog.describe(e), e);
            }
            if (reference == null) {
                throw new LoaderException("Soft type '" + typeId + "' is not defined in this environment");
            }
            cache.put(typeId, reference);
        }
        part.setTypeDefinitionReference(reference);
    }

    public void clear() {
        cache.clear();
    }
}
