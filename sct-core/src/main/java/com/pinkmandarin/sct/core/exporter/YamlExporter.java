package com.pinkmandarin.sct.core.exporter;

import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class YamlExporter {

    private static final Pattern LIST_INDEX_PATTERN = Pattern.compile("^(.+?)\\[(\\d+)]$");

    public void exportAll(List<Property> allProperties, List<Environment> envs, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        var byEnv = allProperties.stream().collect(Collectors.groupingBy(Property::env));

        for (var env : envs) {
            var properties = byEnv.getOrDefault(env.name(), List.of());
            if (properties.isEmpty()) continue;

            var yamlContent = buildYamlForEnv(properties, env.name());
            if (yamlContent != null) {
                Files.writeString(outputDir.resolve(env.toFileName()), yamlContent);
            }
        }
    }

    public String buildYamlForEnv(List<Property> properties, String envName) {
        if (properties.isEmpty()) return null;

        var nested = unflatten(properties);

        if (!Environment.DEFAULT_ENV.equals(envName)) {
            addProfileActivation(nested, envName);
        }

        var yaml = dumpYaml(nested);

        // Insert inline comments from properties
        yaml = insertComments(yaml, properties);

        return yaml;
    }

    /**
     * Post-processes YAML output to insert inline # comments.
     * Tracks indentation to build the full key path for each YAML line,
     * then matches against property section.key to find the correct comment.
     */
    private String insertComments(String yaml, List<Property> properties) {
        // Build map: full property path (section.key) → comment
        var commentMap = new LinkedHashMap<String, String>();
        for (var prop : properties) {
            if (prop.hasComment()) {
                var fullPath = prop.section() + "." + prop.key();
                // Unescape dots from key for matching against YAML output
                fullPath = fullPath.replace("\\.", ".");
                commentMap.put(fullPath, prop.comment());
            }
        }

        if (commentMap.isEmpty()) return yaml;

        // Track YAML key path via indentation
        var lines = yaml.split("\n", -1);
        var sb = new StringBuilder();
        var pathStack = new ArrayList<String>(); // (indent, key) pairs tracked as stack
        var indentStack = new ArrayList<Integer>();

        for (var line : lines) {
            sb.append(line);

            var trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-")) {
                var colonIdx = trimmed.indexOf(':');
                if (colonIdx > 0) {
                    var key = trimmed.substring(0, colonIdx).trim();
                    var indent = line.indexOf(key);

                    // Pop stack until current indent level
                    while (!indentStack.isEmpty() && indentStack.getLast() >= indent) {
                        indentStack.removeLast();
                        pathStack.removeLast();
                    }
                    pathStack.add(key);
                    indentStack.add(indent);

                    // Build full path from stack
                    var fullPath = String.join(".", pathStack);

                    var comment = commentMap.get(fullPath);
                    if (comment != null) {
                        var afterColon = trimmed.substring(colonIdx + 1).trim();
                        if (!afterColon.isEmpty()) {
                            sb.append(" # ").append(comment);
                        }
                    }
                }
            }

            if (!line.isEmpty()) { // skip trailing empty element from split
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> unflatten(List<Property> properties) {
        Map<String, Object> root = new LinkedHashMap<>();

        for (var prop : properties) {
            // Top-level scalar: section is the key, value goes directly to root
            if (YamlImporter.SCALAR_KEY.equals(prop.key())) {
                root.put(prop.section(), castValue(prop.value(), prop.valueType()));
                continue;
            }

            var fullPath = prop.section() + "." + prop.key();
            var parts = splitPath(fullPath);
            Map<String, Object> current = root;

            try {
                for (int i = 0; i < parts.size() - 1; i++) {
                    var part = parts.get(i);
                    var listMatch = LIST_INDEX_PATTERN.matcher(part);

                    if (listMatch.matches()) {
                        var listKey = listMatch.group(1);
                        var index = Integer.parseInt(listMatch.group(2));
                        var list = (List<Object>) current.computeIfAbsent(listKey, k -> new ArrayList<>());
                        ensureListSize(list, index + 1);
                        if (list.get(index) == null) {
                            list.set(index, new LinkedHashMap<String, Object>());
                        }
                        current = (Map<String, Object>) list.get(index);
                    } else {
                        current = (Map<String, Object>) current.computeIfAbsent(part, k -> new LinkedHashMap<>());
                    }
                }

                var lastPart = parts.getLast();
                var listMatch = LIST_INDEX_PATTERN.matcher(lastPart);
                var castedValue = castValue(prop.value(), prop.valueType());

                if (listMatch.matches()) {
                    var listKey = listMatch.group(1);
                    var index = Integer.parseInt(listMatch.group(2));
                    var list = (List<Object>) current.computeIfAbsent(listKey, k -> new ArrayList<>());
                    ensureListSize(list, index + 1);
                    list.set(index, castedValue);
                } else {
                    current.put(lastPart, castedValue);
                }
            } catch (ClassCastException e) {
                throw new IllegalStateException(
                        "Conflicting property path: " + fullPath + " (section=" + prop.section() + ", key=" + prop.key() + ")", e);
            }
        }

        return root;
    }

    static Object castValue(String value, String valueType) {
        if (value == null || Property.NULL_VALUE.equals(value)) {
            return null;
        }
        try {
            return switch (valueType) {
                case "bool" -> Boolean.parseBoolean(value);
                case "int" -> {
                    try {
                        yield Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        yield Long.parseLong(value);
                    }
                }
                case "float" -> Double.parseDouble(value);
                default -> value;
            };
        } catch (NumberFormatException e) {
            // Malformed numeric value — fall back to string
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private void addProfileActivation(Map<String, Object> root, String envName) {
        var spring = (Map<String, Object>) root.computeIfAbsent("spring", k -> new LinkedHashMap<>());
        var config = (Map<String, Object>) spring.computeIfAbsent("config", k -> new LinkedHashMap<>());
        var activate = (Map<String, Object>) config.computeIfAbsent("activate", k -> new LinkedHashMap<>());
        activate.put("on-profile", envName);

        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("config", config);
        for (var entry : spring.entrySet()) {
            if (!"config".equals(entry.getKey())) {
                reordered.put(entry.getKey(), entry.getValue());
            }
        }
        root.put("spring", reordered);

        var reorderedRoot = new LinkedHashMap<String, Object>();
        reorderedRoot.put("spring", root.get("spring"));
        for (var entry : root.entrySet()) {
            if (!"spring".equals(entry.getKey())) {
                reorderedRoot.put(entry.getKey(), entry.getValue());
            }
        }
        root.clear();
        root.putAll(reorderedRoot);
    }

    private String dumpYaml(Map<String, Object> data) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setSplitLines(false);

        var yaml = new Yaml(options);
        return yaml.dump(data);
    }

    private static List<String> splitPath(String path) {
        var parts = new ArrayList<String>();
        var sb = new StringBuilder();

        for (int i = 0; i < path.length(); i++) {
            var ch = path.charAt(i);
            if (ch == '\\' && i + 1 < path.length()) {
                var next = path.charAt(i + 1);
                if (next == '.') {
                    sb.append('.');
                    i++;
                } else if (next == '\\') {
                    sb.append('\\');
                    i++;
                } else {
                    sb.append(ch);
                }
            } else if (ch == '.') {
                if (!sb.isEmpty()) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(ch);
            }
        }
        if (!sb.isEmpty()) {
            parts.add(sb.toString());
        }
        return parts;
    }

    private static void ensureListSize(List<Object> list, int size) {
        while (list.size() < size) {
            list.add(null);
        }
    }
}
