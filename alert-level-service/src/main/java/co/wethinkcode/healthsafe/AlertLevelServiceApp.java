package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 of the HealthSafe pipeline: the hospital Emergency Status, 0-8, with 8
 * being a full Code Blue.
 *
 * <p>State is in-memory and deliberately small: {@code GET} reports it,
 * {@code PUT} validates and changes it. Anything outside 0-8 is a 400, never a
 * silent clamp, so a caller cannot accidentally put the hospital in "level 9".
 */
public class AlertLevelServiceApp {

    static final int MIN_LEVEL = 0;
    static final int MAX_LEVEL = 8;
    static final int HISTORY_LIMIT = 20;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();

    private static int level = 0;
    private static Instant updatedAt = Instant.now();
    private static final List<Map<String, Object>> HISTORY = new ArrayList<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7032);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alert-level", ctx -> ctx.json(snapshot()));

        app.put("/alert-level", ctx -> update(ctx));
        app.post("/alert-level", ctx -> update(ctx));

        app.get("/alert-level/history", ctx -> {
            synchronized (LOCK) {
                ctx.json(HISTORY);
            }
        });
    }

    private static void update(Context ctx) {
        Integer requested = parseLevel(ctx.body());
        if (requested == null) {
            ctx.status(400).json(Map.of(
                    "error", "body must be a JSON number or {\"level\": n}, got: " + abbreviate(ctx.body())));
            return;
        }
        if (requested < MIN_LEVEL || requested > MAX_LEVEL) {
            ctx.status(400).json(Map.of(
                    "error", "level must be between " + MIN_LEVEL + " and " + MAX_LEVEL,
                    "level", requested));
            return;
        }

        Map<String, Object> change;
        synchronized (LOCK) {
            int previous = level;
            level = requested;
            updatedAt = Instant.now();
            change = new LinkedHashMap<>();
            change.put("from", previous);
            change.put("to", requested);
            change.put("at", updatedAt.toString());
            HISTORY.add(change);
            while (HISTORY.size() > HISTORY_LIMIT) {
                HISTORY.remove(0);
            }
        }
        ctx.json(snapshot());
    }

    /** Accepts {@code 5}, {@code "5"} or {@code {"level": 5}}; anything else is null. */
    static Integer parseLevel(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || node.isNull()) {
                return null;
            }
            JsonNode value = node.isObject() ? node.get("level") : node;
            if (value == null || !(value.isNumber() || value.isTextual())) {
                return null;
            }
            String text = value.asText().trim();
            return text.matches("-?\\d+") ? Integer.valueOf(text) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Human-readable meaning of a level, used by staffing-service when sizing a rota. */
    static String describe(int level) {
        if (level <= 1) {
            return "Normal operations";
        }
        if (level <= 3) {
            return "Major incident";
        }
        if (level <= 5) {
            return "Full emergency";
        }
        if (level <= 7) {
            return "Code Blue";
        }
        return "Code Blue (full)";
    }

    private static Map<String, Object> snapshot() {
        synchronized (LOCK) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("level", level);
            out.put("name", describe(level));
            out.put("updatedAt", updatedAt.toString());
            return out;
        }
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 80 ? singleLine : singleLine.substring(0, 77) + "...";
    }
}
