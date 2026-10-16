package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 2/3 of the HealthSafe pipeline: on-call schedules for doctors, sized by
 * the ward and the hospital's current Emergency Status.
 *
 * <p>Each request makes the two synchronous calls the contracts require —
 * ward-service to validate the ward, alert-level-service to read the status —
 * <i>before</i> computing anything, and every dependency failure is mapped to an
 * honest status code instead of a happy-path assumption:
 *
 * <ul>
 *   <li>unknown ward → 404</li>
 *   <li>a dependency is up but has no data / cannot reach <i>its</i> dependencies → 503</li>
 *   <li>a dependency is unreachable → 503 with the reason</li>
 * </ul>
 *
 * <p>After computing, the schedule is also broadcast on
 * {@code staffing-events-topic} (stage 3) so ward-service can react without
 * polling this service.
 */
public class StaffingServiceApp {

    static final int PORT = 7033;
    static final String WARD_SERVICE_URL = "http://localhost:7031";
    static final String ALERT_LEVEL_SERVICE_URL = "http://localhost:7032";

    private static final Logger log = LoggerFactory.getLogger(StaffingServiceApp.class);

    private static StaffingEventPublisher events;

    public static void main(String[] args) {
        events = new StaffingEventPublisher(MqConfig.BROKER_URL, MqConfig.TOPIC);

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/staffing", ctx -> {
            String wardId = ctx.queryParam("wardId");
            if (wardId == null || wardId.isBlank()) {
                ctx.status(400).json(Map.of("error", "missing required query parameter: ?wardId=W-01"));
                return;
            }
            serve(ctx, wardId.trim());
        });

        app.get("/staffing/{wardId}", ctx -> serve(ctx, ctx.pathParam("wardId")));
    }

    private static void serve(Context ctx, String wardId) {
        // 1. Validate the ward against ward-service (404 if it does not exist).
        //    ward-service answers with the canonical id, which we use from here on.
        JsonNode ward;
        String department;
        try {
            HttpResponse<String> response = HttpJson.get(
                    WARD_SERVICE_URL + "/wards/" + URLEncoder.encode(wardId, StandardCharsets.UTF_8));
            switch (response.statusCode()) {
                case 200 -> {
                    ward = HttpJson.MAPPER.readTree(response.body());
                    department = ward.path("department").asText(null);
                }
                case 404 -> {
                    ctx.status(404).json(Map.of("error", "unknown ward: " + wardId));
                    return;
                }
                case 503 -> {
                    ctx.status(503).json(Map.of(
                            "error", "ward-service has no ward data yet: " + response.body()));
                    return;
                }
                default -> {
                    ctx.status(502).json(Map.of(
                            "error", "ward-service returned HTTP " + response.statusCode()));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.status(503).json(Map.of("error", "interrupted while calling ward-service"));
            return;
        } catch (JsonProcessingException e) {
            ctx.status(502).json(Map.of("error", "ward-service returned unreadable JSON"));
            return;
        } catch (IOException e) {
            ctx.status(503).json(Map.of("error", "ward-service unreachable: " + e.getMessage()));
            return;
        }

        // 2. Read the current Emergency Status from alert-level-service.
        int level;
        try {
            HttpResponse<String> response = HttpJson.get(ALERT_LEVEL_SERVICE_URL + "/alert-level");
            if (response.statusCode() != 200) {
                ctx.status(502).json(Map.of(
                        "error", "alert-level-service returned HTTP " + response.statusCode()));
                return;
            }
            JsonNode body = HttpJson.MAPPER.readTree(response.body());
            JsonNode levelNode = body.get("level");
            if (levelNode == null || !levelNode.isInt() || levelNode.asInt() < 0 || levelNode.asInt() > 8) {
                ctx.status(502).json(Map.of(
                        "error", "alert-level-service returned an unusable level: " + response.body()));
                return;
            }
            level = levelNode.asInt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.status(503).json(Map.of("error", "interrupted while calling alert-level-service"));
            return;
        } catch (JsonProcessingException e) {
            ctx.status(502).json(Map.of("error", "alert-level-service returned unreadable JSON"));
            return;
        } catch (IOException e) {
            ctx.status(503).json(Map.of("error", "alert-level-service unreachable: " + e.getMessage()));
            return;
        }

        // 3. Compute it, then broadcast the change so ward-service can react (stage 3).
        String canonicalWardId = ward.path("wardId").asText(wardId);
        Map<String, Object> schedule = ScheduleCalculator.compute(canonicalWardId, department, level);

        Map<String, Object> event = new LinkedHashMap<>(schedule);
        event.put("event", "staffing.schedule.computed");
        boolean published;
        try {
            published = events.publish(HttpJson.MAPPER.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise staffing event: {}", e.getMessage());
            published = false;
        }

        Map<String, Object> response = new LinkedHashMap<>(schedule);
        response.put("eventPublished", published);
        if (!published) {
            response.put("eventPublishNote",
                    "staffing-events-topic unavailable — schedule computed but not broadcast");
        }
        ctx.json(response);
    }
}
