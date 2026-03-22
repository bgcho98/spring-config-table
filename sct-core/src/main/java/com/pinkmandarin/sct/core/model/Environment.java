package com.pinkmandarin.sct.core.model;

import java.util.Comparator;
import java.util.List;

public record Environment(String name) {

    public static final String DEFAULT_ENV = "default";
    public static final String DEFAULT_DISPLAY = "_default";

    private static final List<String> DEFAULT_LIFECYCLE_ORDER = List.of(
            "default", "local", "dev", "alpha", "beta", "real", "release", "dr", "gov"
    );

    /** Default comparator using built-in lifecycle order */
    public static final Comparator<String> ENV_COMPARATOR = comparator(DEFAULT_LIFECYCLE_ORDER);

    /** Create a comparator from a custom lifecycle order list */
    public static Comparator<String> comparator(List<String> lifecycleOrder) {
        return (a, b) -> {
            int pa = priority(a, lifecycleOrder);
            int pb = priority(b, lifecycleOrder);
            if (pa != pb) return Integer.compare(pa, pb);
            return a.compareTo(b);
        };
    }

    private static int priority(String env, List<String> order) {
        var idx = order.indexOf(env);
        if (idx >= 0) return idx * 100;

        // Base-prefix variant: "beta-dr" → after "beta"
        for (int i = 0; i < order.size(); i++) {
            var base = order.get(i);
            if (env.startsWith(base + "-")) {
                return i * 100 + 50;
            }
        }

        // Suffix variant: "ncgn-real" → grouped by lifecycle suffix
        for (int i = 0; i < order.size(); i++) {
            var base = order.get(i);
            if (env.endsWith("-" + base)) {
                return 900 + i;
            }
        }

        return 9999;
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
