package com.pinkmandarin.sct.core.model;

import java.util.Comparator;
import java.util.List;

public record Environment(String name) {

    public static final String DEFAULT_ENV = "default";
    public static final String DEFAULT_DISPLAY = "_default";

    public static final List<String> DEFAULT_LIFECYCLE_ORDER = List.of(
            "default", "local", "dev", "alpha", "beta", "beta-dr", "real", "release", "dr"
    );
    public static final List<String> DEFAULT_REGION_ORDER = List.of(
            "gov", "ncgn", "ngcc", "ngsc", "ninc", "ngovc", "ngoic"
    );

    public static final Comparator<String> ENV_COMPARATOR =
            comparator(DEFAULT_LIFECYCLE_ORDER, DEFAULT_REGION_ORDER);

    /**
     * Two-dimensional sort: region first, lifecycle within region.
     *
     * Parse env name:
     * - Exact lifecycle match → (region=0, lifecycle=idx)
     * - Prefix "{region}-xxx" → (region=idx+1, lifecycle from xxx)
     * - Suffix "xxx-{region}" → (region=idx+1, lifecycle from xxx)
     * - Exact region match → (region=idx+1, lifecycle=MAX)
     * - Unknown → (region=MAX, lifecycle=MAX)
     *
     * Example with gov region, beta lifecycle:
     *   beta-gov → (1, 4, "beta-gov")
     *   gov-beta → (1, 4, "gov-beta")
     *   gov      → (1, 999, "gov")
     */
    public static Comparator<String> comparator(List<String> lifecycleOrder, List<String> regionOrder) {
        return (a, b) -> {
            var ka = sortKey(a, lifecycleOrder, regionOrder);
            var kb = sortKey(b, lifecycleOrder, regionOrder);
            int cmp = Integer.compare(ka[0], kb[0]); // region
            if (cmp != 0) return cmp;
            cmp = Integer.compare(ka[1], kb[1]); // lifecycle
            if (cmp != 0) return cmp;
            return a.compareTo(b); // alphabetical tiebreaker
        };
    }

    private static int[] sortKey(String env, List<String> lifecycle, List<String> regions) {
        // Exact lifecycle match → base region (0)
        var lcIdx = lifecycle.indexOf(env);
        if (lcIdx >= 0) return new int[]{0, lcIdx};

        // Check region prefix: "gov-beta" → region=gov, lifecycle=beta
        for (int r = 0; r < regions.size(); r++) {
            var region = regions.get(r);
            if (env.startsWith(region + "-")) {
                var rest = env.substring(region.length() + 1);
                var li = lifecycle.indexOf(rest);
                return new int[]{r + 1, li >= 0 ? li : 998};
            }
        }

        // Check region suffix: "beta-gov" → region=gov, lifecycle=beta
        for (int r = 0; r < regions.size(); r++) {
            var region = regions.get(r);
            if (env.endsWith("-" + region)) {
                var rest = env.substring(0, env.length() - region.length() - 1);
                var li = lifecycle.indexOf(rest);
                return new int[]{r + 1, li >= 0 ? li : 998};
            }
        }

        // Exact region match → after all lifecycles in that region
        var rIdx = regions.indexOf(env);
        if (rIdx >= 0) return new int[]{rIdx + 1, 999};

        // Unknown
        return new int[]{9999, 9999};
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
