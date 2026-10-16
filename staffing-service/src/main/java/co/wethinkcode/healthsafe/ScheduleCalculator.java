package co.wethinkcode.healthsafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-call rota itself: ward + Emergency Status in, a shift-by-shift list of
 * doctors out.
 *
 * <p>Purely a function of its inputs (no I/O, no clock beyond a timestamp) so it
 * can be unit tested directly and so the same inputs always produce the same rota
 * for a given ward.
 *
 * <p>Rules, kept deliberately simple and explicit:
 * <ul>
 *   <li>Doctors on call: 1 at level 0-1, plus one for every 2 levels above that,
 *       so 5 at a full Code Blue.</li>
 *   <li>Three shifts (Day/Evening/Night); cover is spread as evenly as the head
 *       count allows. When the head count does not divide evenly the last shift
 *       reuses a doctor already on duty and the shortfall is flagged in the
 *       response rather than hidden.</li>
 *   <li>Doctor rotation is seeded from the ward id, so two wards do not get the
 *       identical rota.</li>
 * </ul>
 */
public final class ScheduleCalculator {

    static final List<String> DOCTORS = List.of(
            "Dr. Thandi Mokoena",
            "Dr. Sipho Dlamini",
            "Dr. Aisha Patel",
            "Dr. Johan van der Merwe",
            "Dr. Naledi Khumalo",
            "Dr. Michael O'Brien",
            "Dr. Zanele Ncube",
            "Dr. Karin Botha",
            "Dr. Raj Naidoo",
            "Dr. Lerato Sithole",
            "Dr. Peter Jacobs",
            "Dr. Fatima Hassan");

    static final String[][] SHIFTS = {
            {"Day", "07:00-15:00"},
            {"Evening", "15:00-23:00"},
            {"Night", "23:00-07:00"},
    };

    private ScheduleCalculator() {
    }

    /** 1 doctor at level 0-1, +1 per two levels, 5 at level 8. */
    public static int doctorsRequired(int alertLevel) {
        return 1 + alertLevel / 2;
    }

    public static Map<String, Object> compute(String wardId, String department, int alertLevel) {
        int required = doctorsRequired(alertLevel);
        List<String> rota = rotaFor(wardId, required);

        List<Map<String, Object>> shifts = new ArrayList<>();
        int cursor = 0;
        for (int s = 0; s < SHIFTS.length; s++) {
            int base = required / SHIFTS.length;
            int count = base + (s < required % SHIFTS.length ? 1 : 0);
            List<String> assigned = new ArrayList<>(rota.subList(cursor, cursor + count));
            cursor += count;

            Map<String, Object> shift = new LinkedHashMap<>();
            shift.put("shift", SHIFTS[s][0]);
            shift.put("window", SHIFTS[s][1]);
            if (assigned.isEmpty()) {
                // Not enough doctors for one-per-shift: reuse the first doctor and say so.
                assigned.add(rota.get(0));
                shift.put("coverShortfall", "only " + required + " doctor(s) on call \u2014 "
                        + rota.get(0) + " carries this shift as well");
            }
            shift.put("doctors", assigned);
            shifts.add(shift);
        }

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("wardId", wardId);
        schedule.put("department", department);
        schedule.put("alertLevel", alertLevel);
        schedule.put("alertLevelName", levelName(alertLevel));
        schedule.put("doctorsRequired", required);
        schedule.put("shifts", shifts);
        schedule.put("escalation", escalation(alertLevel));
        schedule.put("generatedAt", Instant.now().toString());
        return schedule;
    }

    static List<String> rotaFor(String wardId, int required) {
        int offset = Math.floorMod(wardId == null ? 0 : wardId.hashCode(), DOCTORS.size());
        List<String> rota = new ArrayList<>(required);
        for (int i = 0; i < required; i++) {
            rota.add(DOCTORS.get((offset + i) % DOCTORS.size()));
        }
        return rota;
    }

    static String escalation(int alertLevel) {
        if (alertLevel <= 2) {
            return null;
        }
        if (alertLevel <= 5) {
            return "Major rota: confirm bed cover at every handover until the alert level drops.";
        }
        if (alertLevel <= 7) {
            return "Recall additional on-call staff; night shift must not run single-doctor.";
        }
        return "Code Blue: all available consultants and registrars to report to the ward.";
    }

    /**
     * Mirror of alert-level-service's own description. Duplicated rather than called
     * over HTTP: it is presentation text for the rota, and ward-service already has
     * the level number it was computed from.
     */
    static String levelName(int alertLevel) {
        if (alertLevel <= 1) {
            return "Normal operations";
        }
        if (alertLevel <= 3) {
            return "Major incident";
        }
        if (alertLevel <= 5) {
            return "Full emergency";
        }
        if (alertLevel <= 7) {
            return "Code Blue";
        }
        return "Code Blue (full)";
    }
}
