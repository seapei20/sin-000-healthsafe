package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ward-service's in-memory copy of the cleaned wards, loaded from
 * ingestion-service.
 *
 * <p>Loading happens in the background rather than at boot so this service stays
 * independently runnable: if ingestion-service is down, {@code /health} still
 * answers and the ward endpoints say why they cannot answer yet (503) instead of
 * the process refusing to start.
 */
public class WardStore {

    private static final Logger log = LoggerFactory.getLogger(WardStore.class);

    private static final long LOADED_REFRESH_MS = 60_000;
    private static final long RETRY_MS = 10_000;

private final String wardsUrl;
private volatile Snapshot snapshot = Snapshot.EMPTY;
private volatile Thread refreshThread;

    public WardStore(String wardsUrl) {
        this.wardsUrl = wardsUrl;
    }

    /** Daemon thread: load immediately, then keep the copy fresh. */
    public void startRefreshing() {
        Thread thread = new Thread(() -> {
            boolean warned = false;
            while (!Thread.currentThread().isInterrupted()) {
                boolean loaded = refresh();
                if (!loaded && !warned) {
                    log.warn("ingestion-service not reachable at {} — ward endpoints return 503 until it is",
                            wardsUrl);
                    warned = true;
                }
                if (loaded) {
                    warned = false;
                }
                pause(loaded ? LOADED_REFRESH_MS : RETRY_MS);
            }
        }, "ward-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    /** Replaces the in-memory copy. Returns false when ingestion-service was unusable. */
    public boolean refresh() {
        try {
            HttpResponse<String> response = HttpJson.get(wardsUrl);
            if (response.statusCode() != 200) {
                log.warn("GET {} returned {}", wardsUrl, response.statusCode());
                return false;
            }
            JsonNode array = HttpJson.MAPPER.readTree(response.body());
            if (!array.isArray()) {
                log.warn("GET {} did not return a JSON array", wardsUrl);
                return false;
            }
            List<JsonNode> wards = new ArrayList<>();
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode ward : array) {
                wards.add(ward);
                String id = canonical(ward.path("wardId").asText(null));
                if (id != null) {
                    byId.put(id, ward);
                }
            }
            snapshot = new Snapshot(List.copyOf(wards), Map.copyOf(byId), Instant.now());
            log.info("Loaded {} wards from ingestion-service", wards.size());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not load wards from ingestion-service: {}", e.getMessage());
            return false;
        }
    }

    public boolean isLoaded() {
        return snapshot.loadedAt != null;
    }

    public Instant loadedAt() {
        return snapshot.loadedAt;
    }

    public List<JsonNode> wards() {
        return snapshot.wards;
    }

    public JsonNode find(String rawId) {
        String wardId = canonical(rawId);
        return wardId == null ? null : snapshot.byId.get(wardId);
    }

    /**
     * Canonicalises any spelling of a ward id ({@code w-5}, {@code W-05}, {@code W 05})
     * to {@code W-05}. Mirrors the rule ingestion-service uses, duplicated here
     * because these modules cannot share code without a parent pom.
     */
    public static String canonical(String rawId) {
        if (rawId == null) {
            return null;
        }
        String digits = rawId.toUpperCase(java.util.Locale.ROOT).replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return String.format("W-%02d", Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), null);

        final List<JsonNode> wards;
        final Map<String, JsonNode> byId;
        final Instant loadedAt;

        Snapshot(List<JsonNode> wards, Map<String, JsonNode> byId, Instant loadedAt) {
            this.wards = wards;
            this.byId = byId;
            this.loadedAt = loadedAt;
        }
    }
}
