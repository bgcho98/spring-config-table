package com.pinkmandarin.sct.core.master;

import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes Property list to a master Markdown config table.
 *
 * Format:
 *   ## section (or ## section.subgroup)
 *   | env | key1 | key2 | ... |
 *   |-----|------|------|-----|
 *   | _default | val | val | ... |
 *   | beta | val | | ... |
 */
public class MasterMarkdownWriter {

    private static final int GROUP_THRESHOLD = 10;
    private static final List<String> PRIORITY_SECTIONS = List.of(
            "server", "spring", "management", "springdoc"
    );

    private List<String> lifecycleOrder = List.of();
    private List<String> regionOrder = List.of();

    /** Set custom environment sort order. If empty, uses defaults. */
    public MasterMarkdownWriter withEnvOrder(List<String> lifecycleOrder, List<String> regionOrder) {
        this.lifecycleOrder = lifecycleOrder;
        this.regionOrder = regionOrder;
        return this;
    }

    public void write(List<Property> properties, Path outputPath) throws IOException {
        var sb = new StringBuilder();
        sb.append("# Application Properties\n\n");
        sb.append("> Edit this file and the Maven/IntelliJ plugin will auto-generate per-environment YAML files.\n");
        sb.append("> `_default` = application.yml, empty cell = inherit default, `null` = explicit null override\n\n");

        var bySection = properties.stream()
                .collect(Collectors.groupingBy(Property::section, () -> new TreeMap<>(sectionComparator()), Collectors.toList()));

        for (var sectionEntry : bySection.entrySet()) {
            var section = sectionEntry.getKey();
            var sectionProps = sectionEntry.getValue();

            var keys = sectionProps.stream()
                    .map(Property::key)
                    .distinct()
                    .sorted()
                    .toList();

            if (keys.size() <= GROUP_THRESHOLD) {
                writeTable(sb, section, "", sectionProps, keys);
            } else {
                writeGroupedTables(sb, section, sectionProps, keys);
            }
        }

        Files.writeString(outputPath, sb.toString());
    }

    private void writeGroupedTables(StringBuilder sb, String section,
                                    List<Property> sectionProps, List<String> allKeys) {
        // Group by first key segment
        var groups = new LinkedHashMap<String, List<String>>();
        for (var key : allKeys) {
            var dotIdx = key.indexOf('.');
            var groupName = dotIdx > 0 ? key.substring(0, dotIdx) : "";
            groups.computeIfAbsent(groupName, k -> new ArrayList<>()).add(key);
        }

        for (var groupEntry : groups.entrySet()) {
            var groupName = groupEntry.getKey();
            var groupKeys = groupEntry.getValue();
            var groupKeySet = new HashSet<>(groupKeys);

            var prefix = groupName;
            var filteredProps = sectionProps.stream()
                    .filter(p -> groupKeySet.contains(p.key()))
                    .toList();

            writeTable(sb, section, prefix, filteredProps, groupKeys);
        }
    }

    private void writeTable(StringBuilder sb, String section, String prefix,
                            List<Property> props, List<String> keys) {
        // Heading
        var heading = prefix.isEmpty() ? section : section + "." + prefix;
        sb.append("## ").append(heading).append("\n\n");

        // Column headers: short names with prefix removed
        var columnNames = new ArrayList<String>();
        for (var key : keys) {
            if (!prefix.isEmpty() && key.startsWith(prefix + ".")) {
                columnNames.add(key.substring(prefix.length() + 1));
            } else {
                columnNames.add(key);
            }
        }

        // env -> key -> value mapping
        var envMap = new LinkedHashMap<String, Map<String, String>>();
        for (var prop : props) {
            var envKey = new Environment(prop.env()).toDisplayName();
            envMap.computeIfAbsent(envKey, k -> new HashMap<>())
                    .put(prop.key(), formatValue(prop));
        }

        // Sort environments by priority order
        var comp = lifecycleOrder.isEmpty() ? Environment.ENV_COMPARATOR
                : Environment.comparator(lifecycleOrder, regionOrder);
        var sortedEnvs = envMap.keySet().stream()
                .sorted((a, b) -> {
                    var ia = Environment.fromDisplayName(a).name();
                    var ib = Environment.fromDisplayName(b).name();
                    return comp.compare(ia, ib);
                })
                .toList();

        // Table header
        sb.append("| env |");
        for (var col : columnNames) {
            sb.append(" ").append(col).append(" |");
        }
        sb.append("\n");

        // Separator
        sb.append("| --- |");
        for (var ignored : columnNames) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // Data rows
        for (var env : sortedEnvs) {
            var values = envMap.get(env);
            sb.append("| ").append(env).append(" |");
            for (var key : keys) {
                var val = values.getOrDefault(key, "");
                sb.append(" ").append(val).append(" |");
            }
            sb.append("\n");
        }

        sb.append("\n");
    }

    private String formatValue(Property prop) {
        var result = formatRawValue(prop);
        // Append HTML comment if property has a comment
        if (prop.hasComment()) {
            var escapedComment = prop.comment().replace("--", "—"); // -- not allowed in HTML comments
            result = result + " <!-- " + escapedComment + " -->";
        }
        return result;
    }

    private String formatRawValue(Property prop) {
        if (prop.isNullValue()) {
            return "null";
        }
        var value = prop.value();
        // Escape backslash first (before other escapes add backslashes)
        value = value.replace("\\", "\\\\");
        // Escape newlines (prevent table row break)
        value = value.replace("\n", "\\n");
        // Escape pipe character
        value = value.replace("|", "\\|");
        // Quote string values that would be misinterpreted by the parser
        if ("string".equals(prop.valueType()) && needsQuoting(value)) {
            // Escape internal quotes before wrapping
            value = "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    private boolean needsQuoting(String value) {
        // Leading/trailing whitespace would be trimmed by parseTableRow
        if (!value.equals(value.trim())) return true;
        // Empty string
        if (value.isEmpty()) return true;
        // "true"/"false" would be parsed as boolean
        if ("true".equals(value) || "false".equals(value)) return true;
        // "null" would be parsed as explicit null
        if ("null".equals(value)) return true;
        // Numeric strings would be parsed as int/long/double
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException ignored) {}
        if (value.contains(".")) {
            try {
                Double.parseDouble(value);
                return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    private static Comparator<String> sectionComparator() {
        return (a, b) -> {
            int ia = PRIORITY_SECTIONS.indexOf(a);
            int ib = PRIORITY_SECTIONS.indexOf(b);
            if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            return a.compareTo(b);
        };
    }
}
