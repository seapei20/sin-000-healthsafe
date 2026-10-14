package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlertLevelServiceTest {

    // ------------------------------------------------------------ parseLevel

    @Test
    void acceptsTheShapesACallerMightSend() {
        assertEquals(5, AlertLevelServiceApp.parseLevel("5"));
        assertEquals(0, AlertLevelServiceApp.parseLevel("0"));
        assertEquals(8, AlertLevelServiceApp.parseLevel("{\"level\": 8}"));
        assertEquals(3, AlertLevelServiceApp.parseLevel(" 3 "));
        assertEquals(7, AlertLevelServiceApp.parseLevel("\"7\""), "a JSON string of digits is still a level");
        assertEquals(-2, AlertLevelServiceApp.parseLevel("{\"level\": -2}"),
                "parsing is separate from range validation");
    }

    @Test
    void rejectsAnythingThatIsNotAnInteger() {
        assertNull(AlertLevelServiceApp.parseLevel(null));
        assertNull(AlertLevelServiceApp.parseLevel(""));
        assertNull(AlertLevelServiceApp.parseLevel("   "));
        assertNull(AlertLevelServiceApp.parseLevel("banana"));
        assertNull(AlertLevelServiceApp.parseLevel("4.5"), "4.5 is not an emergency level");
        assertNull(AlertLevelServiceApp.parseLevel("{\"level\": \"high\"}"));
        assertNull(AlertLevelServiceApp.parseLevel("{\"level\": true}"));
        assertNull(AlertLevelServiceApp.parseLevel("{}"));
        assertNull(AlertLevelServiceApp.parseLevel("null"));
        assertNull(AlertLevelServiceApp.parseLevel("not json at all {"));
    }

    // -------------------------------------------------------------- describe

    @Test
    void describesEveryBandOfTheScale() {
        assertEquals("Normal operations", AlertLevelServiceApp.describe(0));
        assertEquals("Normal operations", AlertLevelServiceApp.describe(1));
        assertEquals("Major incident", AlertLevelServiceApp.describe(2));
        assertEquals("Major incident", AlertLevelServiceApp.describe(3));
        assertEquals("Full emergency", AlertLevelServiceApp.describe(4));
        assertEquals("Full emergency", AlertLevelServiceApp.describe(5));
        assertEquals("Code Blue", AlertLevelServiceApp.describe(6));
        assertEquals("Code Blue", AlertLevelServiceApp.describe(7));
        assertEquals("Code Blue (full)", AlertLevelServiceApp.describe(8), "8 = full Code Blue");
    }
}
