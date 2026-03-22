package com.pinkmandarin.sct.core.model;

public record Property(
        String section,
        String key,
        String env,
        String value,
        String valueType,
        String comment
) {

    public static final String NULL_VALUE = "__NULL__";

    public static Property of(String section, String key, String env, Object rawValue) {
        return of(section, key, env, rawValue, null);
    }

    public static Property of(String section, String key, String env, Object rawValue, String comment) {
        if (rawValue == null) {
            return new Property(section, key, env, NULL_VALUE, "null", comment);
        }
        var valueType = detectValueType(rawValue);
        return new Property(section, key, env, String.valueOf(rawValue), valueType, comment);
    }

    /**
     * Create a Property by parsing a string value and detecting its type.
     * Used by editors where user input is always a String.
     * "true"/"false" → bool, "42" → int, "3.14" → float, else → string.
     */
    public static Property ofParsed(String section, String key, String env, String strValue, String comment) {
        if (strValue == null || strValue.isEmpty()) {
            return new Property(section, key, env, "", "string", comment);
        }
        if ("null".equals(strValue)) {
            return new Property(section, key, env, NULL_VALUE, "null", comment);
        }
        if ("true".equals(strValue) || "false".equals(strValue)) {
            return new Property(section, key, env, strValue, "bool", comment);
        }
        try {
            long l = Long.parseLong(strValue);
            return new Property(section, key, env, strValue, "int", comment);
        } catch (NumberFormatException ignored) {}
        if (strValue.contains(".")) {
            try {
                double d = Double.parseDouble(strValue);
                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                    return new Property(section, key, env, strValue, "float", comment);
                }
            } catch (NumberFormatException ignored) {}
        }
        return new Property(section, key, env, strValue, "string", comment);
    }

    public Property withComment(String comment) {
        return new Property(section, key, env, value, valueType, comment);
    }

    public boolean isNullValue() {
        return NULL_VALUE.equals(value);
    }

    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }

    private static String detectValueType(Object value) {
        if (value instanceof Boolean) return "bool";
        if (value instanceof Integer || value instanceof Long) return "int";
        if (value instanceof Double || value instanceof Float) return "float";
        return "string";
    }
}
