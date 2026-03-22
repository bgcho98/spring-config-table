package com.pinkmandarin.sct.core.master;

import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.ParseResult;
import com.pinkmandarin.sct.core.model.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a master Markdown config table into Property and Environment lists.
 *
 * Format:
 *   ## section (or ## section.prefix)
 *   | env | key1 | key2 |
 *   |-----|------|------|
 *   | _default | val1 | val2 |
 *   | beta | val1 | |
 */
public class MasterMarkdownParser {

    public ParseResult parse(Path masterFile) throws IOException {
        var lines = Files.readAllLines(masterFile, java.nio.charset.StandardCharsets.UTF_8);
        var properties = new ArrayList<Property>();
        var envNames = new LinkedHashSet<String>();
        envNames.add(Environment.DEFAULT_ENV);

        String currentSection = null;
        String currentPrefix = null;
        List<String> columnKeys = null;

        for (var line : lines) {
            var trimmed = line.trim();

            // ## heading -> parse section.prefix
            if (trimmed.startsWith("## ")) {
                var heading = trimmed.substring(3).trim();
                var dotIdx = heading.indexOf('.');
                if (dotIdx > 0) {
                    currentSection = heading.substring(0, dotIdx);
                    currentPrefix = heading.substring(dotIdx + 1);
                } else {
                    currentSection = heading;
                    currentPrefix = "";
                }
                columnKeys = null;
                continue;
            }

            // # heading is ignored (document title)
            if (trimmed.startsWith("#")) {
                continue;
            }

            // Skip non-table rows
            if (!trimmed.startsWith("|") || currentSection == null) {
                continue;
            }

            var cells = parseTableRow(trimmed);
            if (cells.isEmpty()) continue;

            // Skip separator line (|-----|)
            if (cells.get(0).startsWith("-")) {
                continue;
            }

            // Header row: | env | key1 | key2 | ...
            if (columnKeys == null) {
                if ("env".equals(cells.get(0))) {
                    columnKeys = new ArrayList<>(cells.subList(1, cells.size()));
                }
                continue;
            }

            // Data row: | envName | val1 | val2 | ...
            var envKey = cells.get(0);
            if (envKey.isEmpty()) continue;

            var envName = Environment.fromDisplayName(envKey).name();
            envNames.add(envName);

            for (int i = 0; i < columnKeys.size() && i + 1 < cells.size(); i++) {
                var cellValue = cells.get(i + 1);
                if (cellValue.isEmpty()) continue; // empty cell = inherit default

                var columnKey = columnKeys.get(i);
                var fullKey = currentPrefix.isEmpty() ? columnKey : currentPrefix + "." + columnKey;

                if ("null".equals(cellValue)) {
                    properties.add(Property.of(currentSection, fullKey, envName, null));
                } else if (cellValue.startsWith("\"") && cellValue.endsWith("\"") && cellValue.length() >= 2) {
                    // Quoted string — strip quotes, unescape \" then general escapes
                    var inner = cellValue.substring(1, cellValue.length() - 1);
                    inner = inner.replace("\\\"", "\"");
                    inner = unescapeCellValue(inner);
                    properties.add(Property.of(currentSection, fullKey, envName, inner));
                } else {
                    cellValue = unescapeCellValue(cellValue);
                    properties.add(Property.of(currentSection, fullKey, envName, detectAndCast(cellValue)));
                }
            }
        }

        var environments = new ArrayList<Environment>();
        for (var name : envNames) {
            environments.add(new Environment(name));
        }

        return new ParseResult(properties, environments);
    }

    /**
     * Splits a Markdown table row into cells, respecting escaped pipes (\|).
     */
    private List<String> parseTableRow(String line) {
        var cells = new ArrayList<String>();
        var sb = new StringBuilder();
        // Skip leading '|'
        int i = line.indexOf('|');
        if (i < 0) return cells;
        i++; // skip first |

        while (i < line.length()) {
            var ch = line.charAt(i);
            if (ch == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                sb.append('|');
                i += 2;
            } else if (ch == '|') {
                cells.add(sb.toString().trim());
                sb.setLength(0);
                i++;
            } else {
                sb.append(ch);
                i++;
            }
        }
        // Remaining content after last | (no trailing pipe)
        var remaining = sb.toString().trim();
        if (!remaining.isEmpty()) {
            cells.add(remaining);
        }
        // Drop trailing empty cell (from trailing |)
        if (!cells.isEmpty() && cells.getLast().isEmpty()) {
            cells.removeLast();
        }
        return cells;
    }

    /**
     * Unescapes a cell value: \n -> newline, \\" -> ", \\\\ -> backslash.
     * Uses char-by-char parsing to handle escape sequences correctly.
     */
    private String unescapeCellValue(String value) {
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                var next = value.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(ch);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private Object detectAndCast(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            long l = Long.parseLong(value);
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
        } catch (NumberFormatException ignored) {}
        if (value.contains(".")) {
            try {
                double d = Double.parseDouble(value);
                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                    return d;
                }
            } catch (NumberFormatException ignored) {}
        }
        return value;
    }
}
