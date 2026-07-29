package com.plm.tools.partloader;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.plm.tools.partloader.io.CsvReader;
import com.plm.tools.partloader.io.RowMapper;
import com.plm.tools.partloader.model.BomRow;
import com.plm.tools.partloader.model.PartRow;

/**
 * Smoke test for the input layer.
 *
 * <p>Deliberately plain Java with a {@code main} method and no JUnit. The point of this test
 * is that it runs on a laptop with nothing but a JDK — no Windchill installation, no
 * codebase, no database, no method server. That property is worth more than the ergonomics
 * of a test framework, because it means the CSV parsing, defaulting and validation rules can
 * be exercised by whoever is preparing the load file, not only by whoever has a DEV
 * environment.</p>
 *
 * <p>Everything below the mapper needs a real Windchill instance and is verified by the
 * dry-run mode instead — see docs/API_VERIFICATION.md.</p>
 *
 * <pre>
 *   ant test
 *   # or:
 *   javac -d build/test src/main/java/com/plm/tools/partloader/{LoaderConfig.java,util/*.java,model/*.java,io/*.java} \
 *         src/test/java/com/plm/tools/partloader/ParserSmokeTest.java
 *   java -cp build/test com.plm.tools.partloader.ParserSmokeTest samples/parts_sample.csv samples/bom_sample.csv
 * </pre>
 *
 * <p>Exit code 0 when every check passes, 1 otherwise, so it can gate a build.</p>
 */
public final class ParserSmokeTest {

    private static int failures = 0;

    private ParserSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ParserSmokeTest <parts.csv> <bom.csv>");
            System.exit(1);
        }

        LoaderConfig config = LoaderConfig.empty();
        RowMapper mapper = new RowMapper(config);

        System.out.println("--- parts file: " + args[0] + " ---");
        int partCount = 0;
        CsvReader parts = new CsvReader(new File(args[0]), "UTF-8", ',');
        try {
            parts.readHeaders();
            Map<String, String> record;
            while ((record = parts.readRecord()) != null) {
                PartRow row = mapper.toPartRow(record, parts.getLineNumber());
                partCount++;
                System.out.println("  " + row + " ibas=" + row.getIbaValues());

                if (partCount == 1) {
                    // The mapper must prefix the Default cabinet, which the UI folder tree hides.
                    check("folder normalised", "/Default/Parts/Assemblies", row.getFolderPath());
                    check("view defaulted from config", "Design", row.getView());
                }
                if ("SEA-300001".equals(row.getNumber())) {
                    // Quoted field containing the delimiter — the case a naive split(",") gets wrong.
                    check("quoted name with embedded comma", "Gasket, Perimeter Seal", row.getName());
                    check("non-default unit preserved", "m", row.getDefaultUnit());
                    // 2 and not 3: SurfaceTreatment is blank in the file. Blank means
                    // "no value supplied", never "clear the value".
                    check("blank IBA cell dropped", "2", String.valueOf(row.getIbaValues().size()));
                    check("blank IBA key absent", "false",
                            String.valueOf(row.getIbaValues().containsKey("SurfaceTreatment")));
                }
            }
        } finally {
            parts.close();
        }
        check("part row count", "6", String.valueOf(partCount));

        System.out.println("--- bom file: " + args[1] + " ---");
        int bomCount = 0;
        CsvReader bom = new CsvReader(new File(args[1]), "UTF-8", ',');
        try {
            bom.readHeaders();
            Map<String, String> record;
            while ((record = bom.readRecord()) != null) {
                BomRow row = mapper.toBomRow(record, bom.getLineNumber());
                bomCount++;
                System.out.println("  " + row + " unit=" + row.getUnit());
                if ("SEA-300001".equals(row.getChildNumber())) {
                    // Fractional quantities are real: seal and adhesive are consumed by length.
                    check("fractional quantity", "0.85", String.valueOf(row.getQuantity()));
                }
            }
        } finally {
            bom.close();
        }
        check("bom row count", "5", String.valueOf(bomCount));

        System.out.println("--- rows that must be rejected ---");
        expectRejection("missing part number", mapper, Case.MISSING_NUMBER);
        expectRejection("self-referencing usage link", mapper, Case.SELF_REFERENCE);
        expectRejection("non-numeric quantity", mapper, Case.BAD_QUANTITY);
        expectRejection("zero quantity", mapper, Case.ZERO_QUANTITY);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private enum Case { MISSING_NUMBER, SELF_REFERENCE, BAD_QUANTITY, ZERO_QUANTITY }

    private static void check(String label, String expected, String actual) {
        boolean ok = expected.equals(actual);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (ok ? "" : ": expected=" + expected + " actual=" + actual));
    }

    private static void expectRejection(String label, RowMapper mapper, Case scenario) {
        Map<String, String> record = new HashMap<String, String>();
        try {
            switch (scenario) {
                case MISSING_NUMBER:
                    record.put("name", "Bracket");
                    mapper.toPartRow(record, 1);
                    break;
                case SELF_REFERENCE:
                    record.put("parentNumber", "BRK-100000");
                    record.put("childNumber", "BRK-100000");
                    mapper.toBomRow(record, 1);
                    break;
                case BAD_QUANTITY:
                    record.put("parentNumber", "A");
                    record.put("childNumber", "B");
                    record.put("quantity", "abc");
                    mapper.toBomRow(record, 1);
                    break;
                default:
                    record.put("parentNumber", "A");
                    record.put("childNumber", "B");
                    record.put("quantity", "0");
                    mapper.toBomRow(record, 1);
                    break;
            }
            failures++;
            System.out.println("  FAIL " + label + ": expected a LoaderException, none was thrown");
        } catch (Exception e) {
            System.out.println("  ok   " + label + " -> " + e.getMessage());
        }
    }
}
