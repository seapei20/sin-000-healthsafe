package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cleaning rules listed in ingestion-service/README.md, plus the
 * robustness requirement from the rubric: a bad row must never take down the parse.
 */
class WardCleanerTest {

    private static final String HEADER = "ward_id, Wing ,department,beds_available\n";

    private static WardCleaner.CleanResult clean(String csv) {
        return WardCleaner.cleanCsv(csv);
    }

    private static WardRecord only(String csv) {
        WardCleaner.CleanResult result = clean(csv);
        assertEquals(1, result.wards.size(), "expected exactly one cleaned ward");
        return result.wards.get(0);
    }

    private static WardRecord byId(WardCleaner.CleanResult result, String wardId) {
        return result.wards.stream()
                .filter(w -> w.wardId.equals(wardId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ward " + wardId + " in " + result.wards.size()));
    }

    // -------------------------------------------------------- worked example

    @Test
    void readmeWorkedExampleRowIsCleaned() {
        WardRecord ward = only(HEADER + "w-05,east wing ,PAEDIATRICS,five\n");

        assertEquals("W-05", ward.wardId);
        assertEquals("East Wing", ward.wing);
        assertEquals("Paediatrics", ward.department);
        assertNull(ward.bedsAvailable);
        assertTrue(ward.notes.contains("bedsAvailable was non-numeric ('five')"),
                "notes should explain the bad number but was: " + ward.notes);
    }

    @Test
    void nearDuplicateRowsMergeIntoOneRecord() {
        WardCleaner.CleanResult result = clean(HEADER
                + "W-05,East Wing,Paediatrics,5\n"
                + "w-05,east wing ,PAEDIATRICS,five\n");

        assertEquals(1, result.wards.size());
        assertEquals(1, result.duplicatesMerged);
        WardRecord ward = result.wards.get(0);
        assertEquals("W-05", ward.wardId);
        assertEquals(5, ward.bedsAvailable, "the usable value should win over the unusable one");
        assertTrue(ward.notes.contains("duplicate"), ward.notes);
    }

    @Test
    void duplicateFillsMissingFieldsButKeepsFirstSeenOnConflict() {
        WardCleaner.CleanResult result = clean(HEADER
                + "W-07,West Wing,Cardiology,\n"
                + "w-07,east wing,Cardiology,9\n");

        WardRecord ward = result.wards.get(0);
        assertEquals(9, ward.bedsAvailable, "blank base field should be filled from the duplicate");
        assertEquals("West Wing", ward.wing, "first-seen value wins on conflict");
        assertTrue(ward.notes.contains("wing conflict"), ward.notes);
    }

    // ------------------------------------------------------- casing / padding

    @Test
    void casingAndPaddingAreNormalized() {
        assertEquals("W-03", WardCleaner.normalizeWardId("W-03 "));
        assertEquals("W-02", WardCleaner.normalizeWardId("w-02"));
        assertEquals("W-17", WardCleaner.normalizeWardId("w-17"));
        assertEquals("W-05", WardCleaner.normalizeWardId("  W-5 "));
        assertNull(WardCleaner.normalizeWardId("north wing"));

        List<String> notes = new ArrayList<>();
        assertEquals("East Wing", WardCleaner.normalizeWing(" east wing ", notes));
        assertEquals("South Wing", WardCleaner.normalizeWing("South  Wing", notes));
        assertEquals("North Wing", WardCleaner.normalizeWing("north wing", notes));
        assertTrue(notes.isEmpty());
    }

    @Test
    void departmentVariantsCollapseToOneSpelling() {
        List<String> notes = new ArrayList<>();
        assertEquals("Paediatrics", WardCleaner.normalizeDepartment("paediatrics", notes));
        assertEquals("Paediatrics", WardCleaner.normalizeDepartment("Pediatrics", notes));
        assertEquals("Paediatrics", WardCleaner.normalizeDepartment("PAEDIATRICS", notes));
        assertEquals("Cardiology", WardCleaner.normalizeDepartment("cardiology", notes));
        assertEquals("ICU", WardCleaner.normalizeDepartment("ICU", notes));
        assertTrue(notes.isEmpty());
    }

    // ---------------------------------------------------------- numeric rules

    @Test
    void numericColumnHandlesEveryBadInput() {
        List<String> notes = new ArrayList<>();
        assertEquals(3, WardCleaner.parseBeds("3", notes));
        assertEquals(0, WardCleaner.parseBeds("0", notes));
        assertNull(WardCleaner.parseBeds("N/A", notes), "placeholder -> null");
        assertNull(WardCleaner.parseBeds("TBD", notes));
        assertNull(WardCleaner.parseBeds("five", notes));
        assertNull(WardCleaner.parseBeds("full", notes));
        assertNull(WardCleaner.parseBeds("-1", notes), "negative -> null");
        assertNull(WardCleaner.parseBeds("2023", notes), "unrealistic -> null");
        assertTrue(notes.size() == 6, "every bad value should be noted, got " + notes);
    }

    @Test
    void numericParsingDoesNotThrowOnGarbage() {
        List<String> notes = new ArrayList<>();
        assertNull(WardCleaner.parseBeds("12.5 bananas", notes));
        assertNull(WardCleaner.parseBeds("\u2014", notes));
        assertEquals(42, WardCleaner.parseBeds(" 42 ", notes));
    }

    // ------------------------------------------------------------ date rules

    @Test
    void dateFormatsAreNormalizedToIso() {
        List<String> notes = new ArrayList<>();
        assertEquals("2024-03-05", WardCleaner.normalizeDate("2024-03-05", notes));
        assertEquals("2024-03-05", WardCleaner.normalizeDate("3/5/2024", notes), "slash = month first");
        assertEquals("2024-03-05", WardCleaner.normalizeDate("05-03-2024", notes), "dash = day first");
        assertEquals("2024-01-13", WardCleaner.normalizeDate("1/13/2024", notes));
        assertEquals("2024-04-17", WardCleaner.normalizeDate("17/04/2024", notes), "falls back to day-first");
        assertEquals("2024-07-04", WardCleaner.normalizeDate("4-7-2024", notes));
        assertTrue(notes.isEmpty());
    }

    @Test
    void invalidDatesAreRejectedNotCrashed() {
        List<String> notes = new ArrayList<>();
        assertNull(WardCleaner.normalizeDate("2024-13-05", notes), "month 13");
        assertNull(WardCleaner.normalizeDate("2024-02-30", notes), "30 February");
        assertNull(WardCleaner.normalizeDate("N/A", notes), "placeholder -> null");
        assertNull(WardCleaner.normalizeDate("TBD", notes));
        assertNull(WardCleaner.normalizeDate("last tuesday", notes));
        assertEquals(5, notes.size());
        assertTrue(notes.get(0).contains("not a valid date"), notes.get(0));
    }

    // --------------------------------------------------------- boolean rules

    @Test
    void booleanVariantsNormalize() {
        List<String> notes = new ArrayList<>();
        assertEquals(Boolean.TRUE, WardCleaner.parseBoolean("Y", notes));
        assertEquals(Boolean.TRUE, WardCleaner.parseBoolean("yes", notes));
        assertEquals(Boolean.TRUE, WardCleaner.parseBoolean("1", notes));
        assertEquals(Boolean.TRUE, WardCleaner.parseBoolean("true", notes));
        assertEquals(Boolean.FALSE, WardCleaner.parseBoolean("N", notes));
        assertEquals(Boolean.FALSE, WardCleaner.parseBoolean("no", notes));
        assertEquals(Boolean.FALSE, WardCleaner.parseBoolean("0", notes));
        assertEquals(Boolean.FALSE, WardCleaner.parseBoolean("FALSE", notes));
        assertNull(WardCleaner.parseBoolean("-", notes), "placeholder -> null");
        assertNull(WardCleaner.parseBoolean("maybe", notes), "unparseable -> null");
        assertTrue(notes.size() == 2, notes.toString());
    }

    @Test
    void statusCasingNormalizes() {
        List<String> notes = new ArrayList<>();
        assertEquals("Active", WardCleaner.normalizeStatus("Active", notes));
        assertEquals("Active", WardCleaner.normalizeStatus("active", notes));
        assertEquals("Active", WardCleaner.normalizeStatus("ACTIVE", notes));
        assertEquals("Pending", WardCleaner.normalizeStatus("pending", notes));
        assertNull(WardCleaner.normalizeStatus("unknown", notes), "placeholder -> null");
        assertTrue(notes.isEmpty() || notes.get(0).contains("placeholder"), notes.toString());
    }

    // -------------------------------------------------------- robustness

    @Test
    void malformedRowsAreRejectedWithoutAbortingTheParse() {
        WardCleaner.CleanResult result = clean(HEADER
                + "W-01,East Wing,Cardiology,3\n"
                + ",East Wing,Cardiology,3\n"          // no ward id
                + "unknown,West Wing,ICU,2\n"           // ward id is not a number
                + "W-02,West Wing,Maternity,7\n");

        assertEquals(2, result.wards.size(), "good rows must survive bad neighbours");
        assertEquals(2, result.rejected.size());
        assertTrue(result.rejected.get(0).reason.contains("missing ward_id"), result.rejected.get(0).reason);
        assertTrue(result.rejected.get(1).reason.contains("not a ward number"), result.rejected.get(1).reason);
        assertEquals(4, result.rowsRead);
    }

    @Test
    void extraColumnsAreIgnoredWithANote() {
        WardCleaner.CleanResult result = clean(HEADER + "W-01,East Wing,Cardiology,3,legacy comment\n");

        assertEquals(1, result.wards.size());
        assertEquals("W-01", result.wards.get(0).wardId);
        assertEquals(3, result.wards.get(0).bedsAvailable);
        assertTrue(result.wards.get(0).notes.contains("extra column"), result.wards.get(0).notes);
    }

    @Test
    void shortRowIsToleratedRatherThanRejected() {
        WardCleaner.CleanResult result = clean(HEADER + "W-18,East Wing\n");

        assertEquals(1, result.wards.size());
        WardRecord ward = result.wards.get(0);
        assertEquals("East Wing", ward.wing);
        assertNull(ward.department, "missing trailing cells read as blank");
        assertNull(ward.bedsAvailable);
        assertTrue(result.rejected.isEmpty());
    }

    @Test
    void blankLinesAreSkippedQuietly() {
        WardCleaner.CleanResult result = clean(HEADER + "\nW-01,East Wing,Cardiology,3\n, , ,\n");
        assertEquals(1, result.wards.size());
        assertEquals(1, result.rowsRead);
        assertTrue(result.rejected.isEmpty());
    }

    @Test
    void missingRequiredColumnIsAConfigErrorNotARowError() {
        assertThrows(IllegalStateException.class, () -> clean("wing,department\nEast Wing,Cardiology\n"));
    }

    @Test
    void emptyCsvYieldsEmptyResult() {
        assertTrue(clean("").wards.isEmpty());
        assertTrue(clean(HEADER).wards.isEmpty());
    }

    // -------------------------------------------------------- whole fixture

    @Test
    void realFixtureCleansToExpectedWards() {
        String csv;
        try (var in = WardCleanerTest.class.getResourceAsStream("/wards-outdated.csv")) {
            Objects.requireNonNull(in, "fixture missing from test classpath");
            csv = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        WardCleaner.CleanResult result = clean(csv);

        assertEquals(17, result.wards.size(), "18 data rows - 1 duplicate + 0 rejected visible wards");
        assertEquals(1, result.duplicatesMerged);
        assertEquals(2, result.rejected.size(), "the two unidentifiable rows are reported, not served");

        WardRecord w01 = byId(result, "W-01");
        assertEquals("East Wing", w01.wing);
        assertEquals("Cardiology", w01.department);
        assertEquals(3, w01.bedsAvailable);

        WardRecord w05 = byId(result, "W-05");
        assertEquals(5, w05.bedsAvailable, "duplicate merge keeps the usable count");
        assertEquals("Paediatrics", w05.department);
        assertEquals(Boolean.FALSE, w05.specialUnit);
        assertTrue(w05.notes.contains("duplicate row issue: bedsAvailable was non-numeric ('five')"),
                "the discarded duplicate row's own problem is still flagged: " + w05.notes);

        assertNull(byId(result, "W-04").bedsAvailable, "negative beds rejected");
        assertNull(byId(result, "W-13").bedsAvailable, "unrealistic beds rejected");
        assertEquals("ICU", byId(result, "W-09").department);
        assertEquals("South Wing", byId(result, "W-10").wing, "double space collapsed");
        assertEquals("Maternity", byId(result, "W-10").department);
        assertNull(byId(result, "W-08").wing, "blank wing -> null");

        assertEquals("2024-03-05", byId(result, "W-01").lastUpdated);
        assertEquals("2023-12-31", byId(result, "w-17".toUpperCase()).lastUpdated);
        assertNull(byId(result, "W-04").lastUpdated, "invalid date rejected");
        assertNull(byId(result, "W-06").lastUpdated, "placeholder date -> null");

        assertFalse(result.wards.stream().anyMatch(w -> w.wardId == null));
        assertTrue(result.wards.stream().allMatch(w -> w.wardId.matches("W-\\d{2}")));
    }
}
