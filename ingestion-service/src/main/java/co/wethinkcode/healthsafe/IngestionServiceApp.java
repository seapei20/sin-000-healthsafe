package co.wethinkcode.healthsafe;

import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 of the HealthSafe pipeline: reads the messy legacy export
 * {@code wards-outdated.csv}, cleans it once at startup, and serves the result over
 * REST for {@code ward-service} to consume.
 */
public class IngestionServiceApp {

    static final String CSV_RESOURCE = "/wards-outdated.csv";

    private static WardCleaner.CleanResult cleaned;
    private static List<WardRecord> wards;
    private static Map<String, WardRecord> byId;

    public static void main(String[] args) {
        ingest();

        Javalin app = Javalin.create().start(7030);

        app.get("/health", ctx -> ctx.result("OK"));

        // The cleaned records ward-service populates itself from.
        app.get("/wards", ctx -> ctx.json(wards));

        app.get("/wards/{id}", ctx -> {
            WardRecord ward = find(ctx.pathParam("id"));
            if (ward == null) {
                ctx.status(404).json(Map.of("error", "unknown ward: " + ctx.pathParam("id")));
            } else {
                ctx.json(ward);
            }
        });

        app.get("/departments", ctx -> ctx.json(wards.stream()
                .map(w -> w.department)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .sorted()
                .toList()));

        // What happened during the clean: counts plus every rejected row.
        app.get("/report", ctx -> ctx.json(report()));
    }

    /** Reads the CSV from the classpath and replaces the in-memory result. */
    static synchronized void ingest() {
        String csv;
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream(CSV_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + CSV_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                }
                csv = text.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + CSV_RESOURCE + ": " + e.getMessage(), e);
        }

        cleaned = WardCleaner.cleanCsv(csv);
        wards = List.copyOf(cleaned.wards);
        byId = new LinkedHashMap<>();
        for (WardRecord ward : wards) {
            byId.put(ward.wardId, ward);
        }
    }

    /** Case/format tolerant lookup: {@code w-5}, {@code W-05} and {@code W 05} all hit. */
    static WardRecord find(String rawId) {
        String wardId = WardCleaner.normalizeWardId(rawId);
        return wardId == null ? null : byId.get(wardId);
    }

    private static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", "classpath:" + CSV_RESOURCE);
        report.put("generatedAt", Instant.now().toString());
        report.put("rowsRead", cleaned.rowsRead);
        report.put("cleaned", wards.size());
        report.put("duplicatesMerged", cleaned.duplicatesMerged);
        report.put("rejectedCount", cleaned.rejected.size());
        report.put("rejected", cleaned.rejected);
        return report;
    }

    static List<WardRecord> wards() {
        return wards;
    }
}
