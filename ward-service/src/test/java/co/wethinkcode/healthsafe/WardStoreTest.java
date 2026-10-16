package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WardStoreTest {

    @Test
    void canonicalisesEverySpellingOfAWardId() {
        assertEquals("W-05", WardStore.canonical("W-05"));
        assertEquals("W-05", WardStore.canonical("w-05"));
        assertEquals("W-05", WardStore.canonical("  W-5 "));
        assertEquals("W-05", WardStore.canonical("W 05"));
        assertEquals("W-17", WardStore.canonical("w-17"));
        assertEquals("W-10", WardStore.canonical("ward10"));
    }

    @Test
    void rejectsWhatIsNotAWardId() {
        assertNull(WardStore.canonical(null));
        assertNull(WardStore.canonical(""));
        assertNull(WardStore.canonical("   "));
        assertNull(WardStore.canonical("north wing"));
        assertNull(WardStore.canonical("-"));
    }

    @Test
    void aStoreStartsNotLoadedRatherThanThrowing() {
        WardStore store = new WardStore("http://localhost:7030/wards");
        assertEquals(false, store.isLoaded());
        assertNull(store.find("W-01"));
        assertNull(store.loadedAt());
        assertEquals(0, store.wards().size());
    }
}
