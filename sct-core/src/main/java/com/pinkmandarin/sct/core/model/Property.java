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
