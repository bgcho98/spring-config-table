package com.pinkmandarin.sct.core.model;

public record Property(
        String section,
        String key,
        String env,
        String value,
        String valueType
) {

    public static final String NULL_VALUE = "__NULL__";

    public static Property of(String section, String key, String env, Object rawValue) {
        if (rawValue == null) {
            return new Property(section, key, env, NULL_VALUE, "null");
        }
        var valueType = detectValueType(rawValue);
        return new Property(section, key, env, String.valueOf(rawValue), valueType);
    }

    public boolean isNullValue() {
        return NULL_VALUE.equals(value);
    }

    private static String detectValueType(Object value) {
        if (value instanceof Boolean) return "bool";
        if (value instanceof Integer || value instanceof Long) return "int";
        if (value instanceof Double || value instanceof Float) return "float";
        return "string";
    }
}
