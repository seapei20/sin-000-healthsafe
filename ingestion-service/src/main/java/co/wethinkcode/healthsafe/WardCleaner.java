package co.wethinkcode.healthsafe;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans the messy legacy export {@code wards-outdated.csv}.
 *
 * <p>Design notes / assumptions (also called out in the service README):
 * <ul>
 *   <li><b>Bad rows never abort the parse.</b> A row that cannot be identified is
 *       rejected with a reason and the rest of the file is still processed.</li>
 *   <li><b>Untrusted numbers/dates/flags become {@code null}</b> and the reason is
 *       appended to {@code notes}, rather than being silently coerced to 0/false.</li>
 *   <li><b>Duplicates merge first-wins:</b> the first row seen for a canonical id is the
 *       base; missing fields are filled from later duplicates, and conflicting fields
 *       keep the first-seen value with a note recording the conflict.</li>
 *   <li><b>Date ambiguity:</b> {@code -} separated dates are read day-first
 *       ({@code DD-MM-YYYY}), {@code /} separated dates month-first
 *       ({@code MM/DD/YYYY}), matching the formats listed in the README. If the
 *       preferred reading is impossible (e.g. {@code 17/04/2024}) the other reading
 *       is tried before the value is rejected.</li>
 * </ul>
 */
public final class WardCleaner {

    /** A single ward holding more beds than this is treated as a data-entry error. */
    public static final int MAX_BEDS = 200;

    private static final Pattern WARD_ID = Pattern.compile("^W?[\\s_-]*(\\d{1,9})$");

    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "-", "--", "\u2014", "n/a", "na", "n.a.", "n\\a", "tbd", "todo",
            "unknown", "none", "null", "nan", "?", "unspecified");

    private static final Map<String, String> DEPARTMENTS = Map.ofEntries(
            Map.entry("cardiology", "Cardiology"),
            Map.entry("paediatrics", "Paediatrics"),
            Map.entry("pediatrics", "Paediatrics"),
            Map.entry("pediatric", "Paediatrics"),
            Map.entry("paediatric", "Paediatrics"),
            Map.entry("oncology", "Oncology"),
            Map.entry("radiology", "Radiology"),
            Map.entry("icu", "ICU"),
            Map.entry("maternity", "Maternity"),
            Map.entry("emergency", "Emergency"),
            Map.entry("theatre", "Theatre"),
            Map.entry("theater", "Theatre"),
            Map.entry("orthopaedics", "Orthopaedics"),
            Map.entry("orthopedics", "Orthopaedics"));

    private static final Map<String, String> STATUSES = Map.ofEntries(
            Map.entry("active", "Active"),
            Map.entry("inactive", "Inactive"),
            Map.entry("closed", "Closed"),
            Map.entry("pending", "Pending"),
            Map.entry("on hold", "On Hold"),
            Map.entry("on-hold", "On Hold"),
            Map.entry("decommissioned", "Decommissioned"));

    private static final DateTimeFormatter ISO = formatter("uuuu-M-d");
    private static final List<DateTimeFormatter> MONTH_FIRST = List.of(
            formatter("M/d/uuuu"), formatter("M-d/uuuu"), formatter("M-d-uuuu"));
    private static final List<DateTimeFormatter> DAY_FIRST = List.of(
            formatter("d/M/uuuu"), formatter("d-M/uuuu"), formatter("d-M-uuuu"));

    private WardCleaner() {
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    // ------------------------------------------------------------------ public

    /**
     * Parses and cleans a whole CSV document. Never throws for bad <i>data</i>; only
     * for a document that is not a wards CSV at all (missing ward_id column).
     */
    public static CleanResult cleanCsv(String csvText) {
        List<String[]> rows;
        try (CSVReader reader = new CSVReader(new StringReader(csvText))) {
            rows = reader.readAll();
        } catch (IOException | CsvException e) {
            throw new IllegalStateException("Could not parse wards CSV: " + e.getMessage(), e);
        }

        CleanResult result = new CleanResult();
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).length == 0) {
            return result;
        }

        Map<String, Integer> columns = indexColumns(rows.get(0));
        Integer wardIdCol = columns.get("wardid");
        if (wardIdCol == null) {
            throw new IllegalStateException(
                    "wards CSV is missing the required ward_id column (header: " + String.join(",", rows.get(0)) + ")");
        }

        Map<String, Row> byWardId = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            int line = i + 1;
            if (row == null || isBlankRow(row)) {
                continue;
            }
            result.rowsRead++;

            if (wardIdCol >= row.length || blank(row[wardIdCol])) {
                result.reject(line, row, "missing ward_id");
                continue;
            }
            String wardId = normalizeWardId(row[wardIdCol]);
            if (wardId == null) {
                result.reject(line, row, "ward_id '" + collapse(row[wardIdCol]) + "' is not a ward number");
                continue;
            }

            Row cleaned = cleanRow(row, columns, wardId);
            Row existing = byWardId.get(wardId);
            if (existing == null) {
                byWardId.put(wardId, cleaned);
            } else {
                merge(existing, cleaned);
                result.duplicatesMerged++;
            }
        }

        for (Row row : byWardId.values()) {
            row.record.notes = row.notes.isEmpty() ? null : String.join("; ", row.notes);
            result.wards.add(row.record);
        }
        return result;
    }

    /**
     * Canonicalises any spelling of a ward id ({@code w-5}, {@code W-05}, {@code W 05},
     * trailing spaces ...) to {@code W-05}. Returns null when it is not a ward number.
     */
    public static String normalizeWardId(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = WARD_ID.matcher(collapse(raw).toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return String.format("W-%02d", Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- rows

    private static Row cleanRow(String[] row, Map<String, Integer> columns, String wardId) {
        Row out = new Row();
        out.record.wardId = wardId;
        out.rawId = collapse(cell(row, columns.get("wardid")));

        out.record.wing = normalizeWing(cell(row, columns.get("wing")), out.notes);
        out.record.department = normalizeDepartment(cell(row, columns.get("department")), out.notes);
        out.record.bedsAvailable = parseBeds(cell(row, columns.get("bedsavailable")), out.notes);
        out.record.status = normalizeStatus(cell(row, columns.get("status")), out.notes);
        out.record.lastUpdated = normalizeDate(cell(row, columns.get("lastupdated")), out.notes);
        out.record.specialUnit = parseBoolean(cell(row, columns.get("specialunit")), out.notes);

        if (row.length > columns.size()) {
            out.notes.add(row.length + " columns found, " + columns.size()
                    + " expected \u2014 extra column(s) ignored");
        }
        return out;
    }

    private static void merge(Row base, Row duplicate) {
        base.notes.add("duplicate of " + base.record.wardId + " (raw id '" + duplicate.rawId + "') merged");

        if (blank(base.record.wing) && !blank(duplicate.record.wing)) {
            base.record.wing = duplicate.record.wing;
            base.notes.add("wing filled from duplicate");
        } else if (bothPresent(base.record.wing, duplicate.record.wing)
                && !base.record.wing.equals(duplicate.record.wing)) {
            base.notes.add("wing conflict ('" + base.record.wing + "' vs '" + duplicate.record.wing
                    + "') \u2014 kept first-seen");
        }

        if (base.record.department == null && duplicate.record.department != null) {
            base.record.department = duplicate.record.department;
            base.notes.add("department filled from duplicate");
        } else if (bothPresent(base.record.department, duplicate.record.department)
                && !base.record.department.equals(duplicate.record.department)) {
            base.notes.add("department conflict ('" + base.record.department + "' vs '"
                    + duplicate.record.department + "') \u2014 kept first-seen");
        }

        if (base.record.bedsAvailable == null && duplicate.record.bedsAvailable != null) {
            base.record.bedsAvailable = duplicate.record.bedsAvailable;
            base.notes.add("bedsAvailable filled from duplicate (" + duplicate.record.bedsAvailable + ")");
        } else if (base.record.bedsAvailable != null && duplicate.record.bedsAvailable != null
                && !base.record.bedsAvailable.equals(duplicate.record.bedsAvailable)) {
            base.notes.add("bedsAvailable conflict (" + base.record.bedsAvailable + " vs "
                    + duplicate.record.bedsAvailable + ") \u2014 kept first-seen");
        }

        if (base.record.status == null && duplicate.record.status != null) {
            base.record.status = duplicate.record.status;
            base.notes.add("status filled from duplicate");
        } else if (bothPresent(base.record.status, duplicate.record.status)
                && !base.record.status.equals(duplicate.record.status)) {
            base.notes.add("status conflict ('" + base.record.status + "' vs '" + duplicate.record.status
                    + "') \u2014 kept first-seen");
        }

        if (base.record.lastUpdated == null && duplicate.record.lastUpdated != null) {
            base.record.lastUpdated = duplicate.record.lastUpdated;
            base.notes.add("lastUpdated filled from duplicate");
        } else if (bothPresent(base.record.lastUpdated, duplicate.record.lastUpdated)
                && !base.record.lastUpdated.equals(duplicate.record.lastUpdated)) {
            base.notes.add("lastUpdated conflict (" + base.record.lastUpdated + " vs "
                    + duplicate.record.lastUpdated + ") \u2014 kept first-seen");
        }

        if (base.record.specialUnit == null && duplicate.record.specialUnit != null) {
            base.record.specialUnit = duplicate.record.specialUnit;
            base.notes.add("specialUnit filled from duplicate");
        } else if (base.record.specialUnit != null && duplicate.record.specialUnit != null
                && !base.record.specialUnit.equals(duplicate.record.specialUnit)) {
            base.notes.add("specialUnit conflict (" + base.record.specialUnit + " vs "
                    + duplicate.record.specialUnit + ") \u2014 kept first-seen");
        }

        for (String note : duplicate.notes) {
            base.notes.add("duplicate row issue: " + note);
        }
    }

    // ------------------------------------------------------------- field rules

    static String normalizeWing(String raw, List<String> notes) {
        String value = collapse(raw);
        if (value.isEmpty()) {
            notes.add("wing is blank \u2014 normalized to null");
            return null;
        }
        if (isPlaceholder(value)) {
            notes.add("wing placeholder '" + value + "' \u2014 normalized to null");
            return null;
        }
        return titleCase(value);
    }

    static String normalizeDepartment(String raw, List<String> notes) {
        String value = collapse(raw);
        if (value.isEmpty()) {
            notes.add("department is blank \u2014 normalized to null");
            return null;
        }
        if (isPlaceholder(value)) {
            notes.add("department placeholder '" + value + "' \u2014 normalized to null");
            return null;
        }
        String canonical = DEPARTMENTS.get(value.toLowerCase(Locale.ROOT));
        if (canonical != null) {
            return canonical;
        }
        notes.add("department '" + value + "' is not a known department \u2014 flagged for review");
        return titleCase(value);
    }

    static Integer parseBeds(String raw, List<String> notes) {
        String value = collapse(raw);
        if (isPlaceholder(value)) {
            notes.add("bedsAvailable placeholder '" + (value.isEmpty() ? "blank" : value)
                    + "' \u2014 normalized to null");
            return null;
        }
        int beds;
        try {
            beds = Integer.parseInt(value.replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            notes.add("bedsAvailable was non-numeric ('" + value + "') \u2014 flagged for follow-up");
            return null;
        }
        if (beds < 0) {
            notes.add("bedsAvailable was negative (" + beds + ") \u2014 flagged for follow-up");
            return null;
        }
        if (beds > MAX_BEDS) {
            notes.add("bedsAvailable was unrealistic (" + beds + ", max " + MAX_BEDS
                    + ") \u2014 flagged for follow-up");
            return null;
        }
        return beds;
    }

    static String normalizeStatus(String raw, List<String> notes) {
        String value = collapse(raw);
        if (value.isEmpty()) {
            notes.add("status is blank \u2014 normalized to null");
            return null;
        }
        if (isPlaceholder(value)) {
            notes.add("status placeholder '" + value + "' \u2014 normalized to null");
            return null;
        }
        String canonical = STATUSES.get(value.toLowerCase(Locale.ROOT));
        if (canonical != null) {
            return canonical;
        }
        notes.add("status '" + value + "' is not a known status \u2014 flagged for review");
        return titleCase(value);
    }

    static String normalizeDate(String raw, List<String> notes) {
        String value = collapse(raw);
        if (isPlaceholder(value)) {
            notes.add("lastUpdated placeholder '" + (value.isEmpty() ? "blank" : value)
                    + "' \u2014 normalized to null");
            return null;
        }
        List<DateTimeFormatter> candidates = new ArrayList<>();
        if (value.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            candidates.add(ISO);
        }
        if (value.contains("/")) {
            candidates.addAll(MONTH_FIRST);
            candidates.addAll(DAY_FIRST);
        }
        if (value.contains("-")) {
            candidates.addAll(DAY_FIRST);
            candidates.addAll(MONTH_FIRST);
        }
        for (DateTimeFormatter candidate : candidates) {
            try {
                return LocalDate.parse(value, candidate).toString();
            } catch (RuntimeException ignored) {
                // try the next reading
            }
        }
        notes.add("lastUpdated was not a valid date ('" + value + "') \u2014 flagged for follow-up");
        return null;
    }

    static Boolean parseBoolean(String raw, List<String> notes) {
        String value = collapse(raw).toLowerCase(Locale.ROOT);
        if (value.equals("y") || value.equals("yes") || value.equals("true") || value.equals("t")
                || value.equals("1") || value.equals("on")) {
            return Boolean.TRUE;
        }
        if (value.equals("n") || value.equals("no") || value.equals("false") || value.equals("f")
                || value.equals("0") || value.equals("off")) {
            return Boolean.FALSE;
        }
        if (isPlaceholder(value)) {
            notes.add("specialUnit placeholder '" + (value.isEmpty() ? "blank" : collapse(raw))
                    + "' \u2014 normalized to null");
            return null;
        }
        notes.add("specialUnit was not a boolean ('" + collapse(raw) + "') \u2014 flagged for follow-up");
        return null;
    }

    // -------------------------------------------------------------- primitives

    private static Map<String, Integer> indexColumns(String[] header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            columns.putIfAbsent(slug(header[i]), i);
        }
        return columns;
    }

    private static String slug(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(String[] row, Integer index) {
        if (index == null || index < 0 || index >= row.length) {
            return "";
        }
        return row[index];
    }

    private static boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (!blank(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || collapse(value).isEmpty();
    }

    private static boolean bothPresent(String left, String right) {
        return left != null && right != null;
    }

    /** Trim and collapse every run of internal whitespace down to a single space. */
    static String collapse(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isPlaceholder(String value) {
        return PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            boolean acronym = word.length() <= 3 && word.equals(word.toUpperCase(Locale.ROOT))
                    && word.chars().anyMatch(Character::isLetter);
            if (acronym) {
                out.append(word);
            } else {
                out.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------- structures

    /** A cleaned row plus the running list of everything that was fixed on it. */
    private static final class Row {
        final WardRecord record = new WardRecord();
        final List<String> notes = new ArrayList<>();
        String rawId;
    }

    /** Outcome of cleaning one CSV document. */
    public static final class CleanResult {
        public final List<WardRecord> wards = new ArrayList<>();
        public final List<RejectedRow> rejected = new ArrayList<>();
        public int rowsRead;
        public int duplicatesMerged;

        void reject(int line, String[] row, String reason) {
            RejectedRow rejectedRow = new RejectedRow();
            rejectedRow.line = line;
            rejectedRow.raw = String.join(",", row);
            rejectedRow.reason = reason;
            rejected.add(rejectedRow);
        }
    }

    /** A row that could not be turned into a ward record, kept for the ingestion report. */
    public static final class RejectedRow {
        public int line;
        public String raw;
        public String reason;
    }
}
