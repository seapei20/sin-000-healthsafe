package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleCalculatorTest {

    @Test
    void headCountScalesWithEmergencyStatus() {
        assertEquals(1, ScheduleCalculator.doctorsRequired(0));
        assertEquals(1, ScheduleCalculator.doctorsRequired(1));
        assertEquals(2, ScheduleCalculator.doctorsRequired(2));
        assertEquals(3, ScheduleCalculator.doctorsRequired(5));
        assertEquals(4, ScheduleCalculator.doctorsRequired(7));
        assertEquals(5, ScheduleCalculator.doctorsRequired(8), "a full Code Blue needs the most cover");
    }

    @Test
    void everyShiftIsCoveredEvenAtLevelZero() {
        Map<String, Object> schedule = ScheduleCalculator.compute("W-01", "Cardiology", 0);

        assertEquals(1, schedule.get("doctorsRequired"));
        List<Map<String, Object>> shifts = shifts(schedule);
        assertEquals(3, shifts.size(), "Day, Evening and Night");
        for (Map<String, Object> shift : shifts) {
            assertFalse(doctors(shift).isEmpty(), "shift " + shift.get("shift") + " has nobody on it");
        }
    }

    @Test
    void shortfallIsFlaggedRatherThanHidden() {
        // 1 doctor cannot cover 3 shifts on its own — two shifts must borrow.
        Map<String, Object> schedule = ScheduleCalculator.compute("W-02", "ICU", 0);
        List<Map<String, Object>> shifts = shifts(schedule);

        assertFalse(shifts.get(0).containsKey("coverShortfall"), "day shift is genuinely covered");
        assertTrue(shifts.get(1).containsKey("coverShortfall"), "evening shift reuses the on-call doctor");
        assertTrue(shifts.get(2).containsKey("coverShortfall"), "night shift reuses the on-call doctor");
        assertEquals(doctors(shifts.get(0)), doctors(shifts.get(1)), "same doctor carries the borrowed shift");
    }

    @Test
    void fullCodeBlueSpreadAcrossShiftsWithoutShortfall() {
        Map<String, Object> schedule = ScheduleCalculator.compute("W-03", "Oncology", 8);
        List<Map<String, Object>> shifts = shifts(schedule);

        assertEquals(5, schedule.get("doctorsRequired"));
        int assigned = 0;
        for (Map<String, Object> shift : shifts) {
            assertFalse(shift.containsKey("coverShortfall"), "level 8 must not leave a shift short");
            assigned += doctors(shift).size();
        }
        assertEquals(5, assigned, "all five on-call doctors appear exactly once");
    }

    @Test
    void sameInputsProduceTheSameRota() {
        Map<String, Object> first = ScheduleCalculator.compute("W-07", "Maternity", 4);
        Map<String, Object> second = ScheduleCalculator.compute("W-07", "Maternity", 4);

        assertEquals(doctorNames(first), doctorNames(second), "a rota must be reproducible");
        assertNotNull(first.get("generatedAt"));
    }

    @Test
    void rotationIsSeededFromTheWardIdSoWardsDiffer() {
        java.util.Set<String> firstDoctors = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= 12; i++) {
            firstDoctors.add(ScheduleCalculator.rotaFor(String.format("W-%02d", i), 3).get(0));
        }
        assertTrue(firstDoctors.size() > 1,
                "expected more than one rotation across 12 wards, got " + firstDoctors.size());

        List<String> one = ScheduleCalculator.rotaFor("W-01", 3);
        assertEquals(one, ScheduleCalculator.rotaFor("W-01", 3), "same ward, same rota");
        assertEquals(3, one.size());
        assertFalse(one.contains(null));
    }

    @Test
    void escalationOnlyAppearsWhenItIsWarranted() {
        assertNull(ScheduleCalculator.escalation(0));
        assertNull(ScheduleCalculator.escalation(2));
        assertNotNull(ScheduleCalculator.escalation(3));
        assertTrue(ScheduleCalculator.escalation(8).contains("Code Blue"));
    }

    @Test
    void levelNamesMatchAlertLevelService() {
        assertEquals("Normal operations", ScheduleCalculator.levelName(0));
        assertEquals("Major incident", ScheduleCalculator.levelName(3));
        assertEquals("Full emergency", ScheduleCalculator.levelName(5));
        assertEquals("Code Blue (full)", ScheduleCalculator.levelName(8));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shifts(Map<String, Object> schedule) {
        return (List<Map<String, Object>>) schedule.get("shifts");
    }

    @SuppressWarnings("unchecked")
    private static List<String> doctors(Map<String, Object> shift) {
        return (List<String>) shift.get("doctors");
    }

    private static List<String> doctorNames(Map<String, Object> schedule) {
        List<String> names = new java.util.ArrayList<>();
        for (Map<String, Object> shift : shifts(schedule)) {
            names.addAll(doctors(shift));
        }
        return names;
    }
}
