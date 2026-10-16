package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 2/3/4 of the HealthSafe pipeline: the ward-facing service.
 *
 * <ul>
 *   <li>Stage 2 — serves wards/departments sourced from ingestion-service, and
 *       answers "what is the on-call rota for this ward?"</li>
 *   <li>Stage 3 — that rota now arrives on {@code staffing-events-topic} and is
 *       answered from {@link StaffingCache}; a synchronous call to
 *       staffing-service remains only for a cold cache (see
 *       {@link #serveStaffing}).</li>
 *   <li>Stage 4 — reports equipment failures onto {@code equipment-failure-queue}
 *       for equipment-alert-service to consume with guaranteed delivery.</li>
 * </ul>
 */
public class WardServiceApp {

    static final int PORT = 7031;
    static final String INGESTION_URL = "http://localhost:7030";
    static final String STAFFING_URL = "http://localhost:7033";

    private static final Logger log = LoggerFactory.getLogger(WardServiceApp.class);

    private static WardStore store;
    private static StaffingCache staffingCache;
    private static EquipmentFailurePublisher equipmentFailures;

    public static void main(String[] args) {
        store = new WardStore(INGESTION_URL + "/wards");
        store.startRefreshing();

        staffingCache = new StaffingCache();
        StaffingEventsSubscriber subscriber =
                new StaffingEventsSubscriber(MqConfig.BROKER_URL, MqConfig.TOPIC, staffingCache);
        subscriber.start();

        equipmentFailures = new EquipmentFailurePublisher(MqConfig.BROKER_URL, MqConfig.QUEUE);

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        // ---- wards / departments, sourced from ingestion-service ----------------

        app.get("/wards", ctx -> {
            if (!requireLoaded(ctx)) {
                return;
            }
            ctx.json(store.wards());
        });

        app.get("/wards/{id}", ctx -> {
            JsonNode ward = store.find(ctx.pathParam("id"));
            if (ward == null) {
                wardNotFound(ctx, ctx.pathParam("id"));
                return;
            }
            ctx.json(ward);
        });

        app.get("/departments", ctx -> {
            if (!requireLoaded(ctx)) {
                return;
            }
            ctx.json(store.wards().stream()
                    .map(w -> w.path("department").asText(null))
                    .filter(d -> d != null && !d.isBlank())
                    .distinct()
                    .sorted()
                    .toList());
        });

        app.post("/wards/refresh", ctx -> {
            if (store.refresh()) {
                ctx.json(Map.of("status", "reloaded", "wards", store.wards().size()));
            } else {
                ctx.status(503).json(Map.of("error", "ingestion-service unavailable at " + INGESTION_URL));
            }
        });

        // ---- on-call staffing (stage 3: topic first, stage 2 REST as fallback) --

        app.get("/wards/{id}/staffing", ctx -> serveStaffing(ctx, false));
        app.post("/wards/{id}/staffing/refresh", ctx -> serveStaffing(ctx, true));

        /** Everything the topic has broadcast so far — handy when demonstrating stage 3. */
        app.get("/staffing-events", ctx -> ctx.json(staffingCache.all()));

        // ---- equipment failures (stage 4: guaranteed delivery via a queue) ------

        app.post("/wards/{id}/equipment-failures", WardServiceApp::publishEquipmentFailure);
    }

    /**
     * Serves the on-call rota for a ward.
     *
     * <p>Cached topic events win: that is the stage-3 behaviour. When nothing has
     * been received yet (cold start, or the broker has never been up) the service
     * falls back to the stage-2 synchronous call, and labels the response
     * {@code source} so the path taken is visible to whoever is testing this.
     */
    private static void serveStaffing(Context ctx, boolean forceRest) {
        JsonNode ward = store.find(ctx.pathParam("id"));
        if (ward == null) {
            wardNotFound(ctx, ctx.pathParam("id"));
            return;
        }
        String wardId = ward.path("wardId").asText();

        if (!forceRest) {
            JsonNode cached = staffingCache.get(wardId);
            if (cached != null) {
                ctx.json(envelope(wardId, "mq", cached));
                return;
            }
        }

        String url = STAFFING_URL + "/staffing?wardId="
                + URLEncoder.encode(wardId, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = HttpJson.get(url);
            switch (response.statusCode()) {
                case 200 -> ctx.json(envelope(wardId, "rest", HttpJson.MAPPER.readTree(response.body())));
                case 404 -> ctx.status(404)
                        .json(Map.of("error", "staffing-service does not know ward " + wardId));
                case 503 -> ctx.status(503)
                        .json(Map.of("error", "staffing-service could not reach ward-service/alert-level-service: "
                                + response.body()));
                default -> ctx.status(502)
                        .json(Map.of("error", "staffing-service returned HTTP " + response.statusCode()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.status(503).json(Map.of("error", "interrupted while calling staffing-service"));
        } catch (JsonProcessingException e) {
            ctx.status(502).json(Map.of("error", "staffing-service returned unreadable JSON"));
        } catch (IOException e) {
            ctx.status(503).json(Map.of("error", "staffing-service unreachable: " + e.getMessage()));
        }
    }

    private static void publishEquipmentFailure(Context ctx) {
        JsonNode ward = store.find(ctx.pathParam("id"));
        if (ward == null) {
            wardNotFound(ctx, ctx.pathParam("id"));
            return;
        }

        JsonNode body = null;
        if (!ctx.body().isBlank()) {
            try {
                body = HttpJson.MAPPER.readTree(ctx.body());
            } catch (JsonProcessingException e) {
                ctx.status(400).json(Map.of("error",
                        "body must be JSON, e.g. {\"equipment\": \"Infusion pump\", \"fault\": \"occlusion alarm\"}"));
                return;
            }
        }
        String equipment = body == null ? "" : body.path("equipment").asText("").trim();
        if (equipment.isEmpty()) {
            ctx.status(400).json(Map.of("error", "body must include a non-empty \"equipment\" field"));
            return;
        }

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("alertId", UUID.randomUUID().toString());
        alert.put("wardId", ward.path("wardId").asText());
        alert.put("wing", ward.path("wing").isNull() ? null : ward.path("wing").asText());
        alert.put("department", ward.path("department").isNull() ? null : ward.path("department").asText());
        alert.put("equipment", equipment);
        alert.put("fault", body.path("fault").asText("").trim());
        alert.put("severity", body.path("severity").asText("critical").trim());
        alert.put("detectedAt", Instant.now().toString());
        alert.put("reportedBy", "ward-service");

        try {
            equipmentFailures.publish(HttpJson.MAPPER.writeValueAsString(alert));
        } catch (JMSException e) {
            // Be honest: if the queue did not take it, the caller must retry.
            log.warn("Could not queue equipment failure for {}: {}", alert.get("wardId"), e.getMessage());
            ctx.status(503).json(Map.of(
                    "error", "equipment-failure-queue unavailable, alert NOT queued: " + e.getMessage(),
                    "alert", alert));
            return;
        } catch (JsonProcessingException e) {
            ctx.status(500).json(Map.of("error", "could not serialise alert: " + e.getMessage()));
            return;
        }

        ctx.status(202).json(Map.of("status", "queued", "alert", alert));
    }

    private static Map<String, Object> envelope(String wardId, String source, JsonNode schedule) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wardId", wardId);
        out.put("source", source);
        out.put("schedule", schedule);
        return out;
    }

    private static boolean requireLoaded(Context ctx) {
        if (store.isLoaded()) {
            return true;
        }
        ctx.status(503).json(Map.of(
                "error", "ward data not loaded yet from ingestion-service",
                "ingestion", INGESTION_URL));
        return false;
    }

    private static void wardNotFound(Context ctx, String rawId) {
        if (!store.isLoaded()) {
            ctx.status(503).json(Map.of(
                    "error", "cannot look up wards yet — ward data not loaded from ingestion-service",
                    "ingestion", INGESTION_URL));
        } else {
            ctx.status(404).json(Map.of("error", "unknown ward: " + rawId));
        }
    }
}
