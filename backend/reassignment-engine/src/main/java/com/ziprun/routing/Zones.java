package com.ziprun.routing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivery zones (Bengaluru areas) and which ones border each other.
 *
 * Distance is deliberately coarse: same zone, neighbouring zone, or further away.
 * It's what dispatchers reason with, needs no maps API, and is easy to explain
 * in a suggestion's reasoning. A real deployment would load this from config.
 */
public final class Zones {

    public record Zone(String id, String name, List<String> neighbours) {
    }

    /** Coarse distance between two zones. UNKNOWN when either side has no zone. */
    public enum Distance {
        SAME(0), NEIGHBOUR(1), FAR(3), UNKNOWN(2);

        /** Penalty in "orders" added to an agent's load when ranking (rule-based). */
        public final int penalty;

        Distance(int penalty) {
            this.penalty = penalty;
        }
    }

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<String, Set<String>> NEIGHBOURS = new HashMap<>();

    static {
        zone("KORAMANGALA", "Koramangala", "HSR_LAYOUT", "BTM_LAYOUT", "INDIRANAGAR", "BELLANDUR", "JAYANAGAR");
        zone("HSR_LAYOUT", "HSR Layout", "BTM_LAYOUT", "BELLANDUR", "ELECTRONIC_CITY");
        zone("BTM_LAYOUT", "BTM Layout", "JAYANAGAR", "JP_NAGAR");
        zone("INDIRANAGAR", "Indiranagar", "MG_ROAD", "MARATHAHALLI");
        zone("MG_ROAD", "MG Road", "JAYANAGAR", "MALLESHWARAM");
        zone("WHITEFIELD", "Whitefield", "MARATHAHALLI");
        zone("MARATHAHALLI", "Marathahalli", "BELLANDUR");
        zone("BELLANDUR", "Bellandur", "ELECTRONIC_CITY");
        zone("ELECTRONIC_CITY", "Electronic City");
        zone("JAYANAGAR", "Jayanagar", "JP_NAGAR", "BANASHANKARI");
        zone("JP_NAGAR", "JP Nagar", "BANASHANKARI");
        zone("BANASHANKARI", "Banashankari");
        zone("MALLESHWARAM", "Malleshwaram", "RAJAJINAGAR", "YESHWANTHPUR");
        zone("RAJAJINAGAR", "Rajajinagar", "YESHWANTHPUR");
        zone("YESHWANTHPUR", "Yeshwanthpur", "PEENYA");
        zone("PEENYA", "Peenya");
    }

    private Zones() {
    }

    /** Declares a zone; borders are symmetric, so each pair is listed once. */
    private static void zone(String id, String name, String... borders) {
        NAMES.put(id, name);
        NEIGHBOURS.computeIfAbsent(id, k -> new HashSet<>());
        for (String other : borders) {
            NEIGHBOURS.get(id).add(other);
            NEIGHBOURS.computeIfAbsent(other, k -> new HashSet<>()).add(id);
        }
    }

    public static List<Zone> all() {
        return NAMES.entrySet().stream()
            .map(e -> new Zone(e.getKey(), e.getValue(), NEIGHBOURS.get(e.getKey()).stream().sorted().toList()))
            .toList();
    }

    public static boolean isKnown(String id) {
        return id != null && NAMES.containsKey(id);
    }

    /** Display name, or the raw value for anything unknown (null stays null). */
    public static String name(String id) {
        return id == null ? null : NAMES.getOrDefault(id, id);
    }

    public static Distance distance(String from, String to) {
        if (!isKnown(from) || !isKnown(to)) {
            return Distance.UNKNOWN;
        }
        if (from.equals(to)) {
            return Distance.SAME;
        }
        return NEIGHBOURS.get(from).contains(to) ? Distance.NEIGHBOUR : Distance.FAR;
    }
}
