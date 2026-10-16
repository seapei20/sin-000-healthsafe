package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest staffing event per ward, fed by the {@code staffing-events-topic}
 * subscription.
 *
 * <p>This is what replaced ward-service's stage-2 synchronous call to
 * staffing-service: instead of polling, ward-service reacts to each broadcast and
 * answers from here.
 */
public class StaffingCache {

    private final Map<String, JsonNode> byWard = new ConcurrentHashMap<>();

    /** Called for every message received on the topic. */
    public void record(JsonNode event) {
        String wardId = event.path("wardId").asText("");
        if (!wardId.isBlank()) {
            byWard.put(wardId, event);
        }
    }

    /** The most recent event seen for this ward, or null if none has arrived yet. */
    public JsonNode get(String wardId) {
        return byWard.get(wardId);
    }

    /** Everything received so far, for debugging/inspection of the topic. */
    public Map<String, JsonNode> all() {
        return new LinkedHashMap<>(byWard);
    }
}
