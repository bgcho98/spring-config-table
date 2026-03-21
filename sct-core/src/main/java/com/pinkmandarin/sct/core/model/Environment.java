package com.pinkmandarin.sct.core.model;

public record Environment(String name) {

    public static final String DEFAULT_ENV = "default";
    public static final String DEFAULT_DISPLAY = "_default";

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
