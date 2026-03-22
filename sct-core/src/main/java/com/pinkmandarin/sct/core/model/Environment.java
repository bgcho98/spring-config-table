package com.pinkmandarin.sct.core.model;

import java.util.Comparator;
import java.util.List;

public record Environment(String name) {

    public static final String DEFAULT_ENV = "default";
    public static final String DEFAULT_DISPLAY = "_default";

    /**
     * Lifecycle-based priority. Environments are sorted by matching
     * their base profile (before first '-') against this list.
     * Variants like "gov-beta" sort after their base ("gov").
     * Unknown profiles sort at the end alphabetically.
     */
    private static final List<String> LIFECYCLE_ORDER = List.of(
            "default", "local", "dev", "alpha", "beta", "real", "release", "dr", "gov"
    );

    public static final Comparator<String> ENV_COMPARATOR = (a, b) -> {
        int pa = priority(a);
        int pb = priority(b);
        if (pa != pb) return Integer.compare(pa, pb);
        return a.compareTo(b); // same priority group → alphabetical
    };

    private static int priority(String env) {
        // Exact match first
        var idx = LIFECYCLE_ORDER.indexOf(env);
        if (idx >= 0) return idx * 100;

        // Check if env starts with a known base (e.g., "gov-beta" → base "gov")
        for (int i = 0; i < LIFECYCLE_ORDER.size(); i++) {
            var base = LIFECYCLE_ORDER.get(i);
            if (env.startsWith(base + "-")) {
                return i * 100 + 50; // after the base, before next group
            }
        }

        // Check if env contains a known lifecycle as suffix (e.g., "ncgn-real" → "real")
        for (int i = 0; i < LIFECYCLE_ORDER.size(); i++) {
            var base = LIFECYCLE_ORDER.get(i);
            if (env.endsWith("-" + base)) {
                return 900 + i; // after all known groups, sub-sorted by lifecycle
            }
        }

        return 9999; // unknown → end
    }

    public String toFileName() {
        if (DEFAULT_ENV.equals(name)) {
            return "application.yml";
        }
        return "application-" + name + ".yml";
    }

    public String toDisplayName() {
        return DEFAULT_ENV.equals(name) ? DEFAULT_DISPLAY : name;
    }

    public static Environment fromDisplayName(String displayName) {
        return new Environment(DEFAULT_DISPLAY.equals(displayName) ? DEFAULT_ENV : displayName);
    }
}
