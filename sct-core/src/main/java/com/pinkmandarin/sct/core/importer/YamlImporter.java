package com.pinkmandarin.sct.core.importer;

import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.ParseResult;
import com.pinkmandarin.sct.core.model.Property;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class YamlImporter {

    private static final Pattern PROFILE_PATTERN = Pattern.compile("application-(.+)\\.ya?ml");
    private static final String ON_PROFILE_KEY = "config.activate.on-profile";
    public static final String SCALAR_KEY = "__scalar__";

    public ParseResult importDirectory(Path dir) throws IOException {
        List<Path> yamlFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            yamlFiles = stream
                    .filter(p -> p.getFileName().toString().matches("application(-[^.]+)?\\.ya?ml"))
                    .sorted()
                    .toList();
        }
        return importFiles(yamlFiles);
    }

    public ParseResult importFiles(List<Path> yamlFiles) throws IOException {
        var envs = new ArrayList<Environment>();
        var allProperties = new ArrayList<Property>();
        var defaultProperties = new HashMap<String, Property>();

        // Separate default and profile files, default first
        Path defaultFile = null;
        var profileFiles = new ArrayList<Path>();

        for (var file : yamlFiles) {
            if (extractEnvName(file).equals(Environment.DEFAULT_ENV)) {
                defaultFile = file;
            } else {
                profileFiles.add(file);
            }
        }

        // Process default first
        if (defaultFile != null) {
            envs.add(new Environment(Environment.DEFAULT_ENV));
            var props = parseFile(defaultFile, Environment.DEFAULT_ENV);
            allProperties.addAll(props);
            for (var p : props) {
                defaultProperties.put(p.section() + "." + p.key(), p);
            }
        }

        // Profile files — remove values identical to default
        for (var file : profileFiles) {
            var envName = extractEnvName(file);
            envs.add(new Environment(envName));

            var props = parseFile(file, envName);
            var filtered = props.stream()
                    .filter(p -> {
                        var defaultProp = defaultProperties.get(p.section() + "." + p.key());
                        return defaultProp == null || !Objects.equals(p.value(), defaultProp.value());
                    })
                    .toList();
            allProperties.addAll(filtered);
        }

        return new ParseResult(allProperties, envs);
    }

    @SuppressWarnings("unchecked")
    public List<Property> parseFile(Path file, String envName) throws IOException {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        var properties = new ArrayList<Property>();

        try (var reader = Files.newBufferedReader(file)) {
            for (Object doc : yaml.loadAll(reader)) {
                if (doc == null) continue;
                if (!(doc instanceof Map)) continue;

                var data = (Map<String, Object>) doc;
                for (var entry : data.entrySet()) {
                    var section = entry.getKey();
                    var value = entry.getValue();

                    if (value instanceof Map) {
                        flatten(section, "", (Map<String, Object>) value, envName, properties);
                    } else {
                        properties.add(Property.of(section, SCALAR_KEY, envName, value));
                    }
                }
            }
        }

        // Remove spring.config.activate.on-profile (auto-generated on export)
        properties.removeIf(p ->
                "spring".equals(p.section()) && ON_PROFILE_KEY.equals(p.key()));

        // Extract inline comments from raw YAML and attach to properties
        attachComments(file, properties);

        return properties;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String section, String prefix, Map<String, Object> map,
                         String envName, List<Property> result) {
        for (var entry : map.entrySet()) {
            // Escape backslash first, then dots in YAML keys
            var escapedKey = entry.getKey().replace("\\", "\\\\").replace(".", "\\.");
            var key = prefix.isEmpty() ? escapedKey : prefix + "." + escapedKey;
            var value = entry.getValue();

            if (value instanceof Map) {
                flatten(section, key, (Map<String, Object>) value, envName, result);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    var item = list.get(i);
                    if (item instanceof Map) {
                        flatten(section, key + "[" + i + "]", (Map<String, Object>) item, envName, result);
                    } else {
                        result.add(Property.of(section, key + "[" + i + "]", envName, item));
                    }
                }
            } else {
                result.add(Property.of(section, key, envName, value));
            }
        }
    }

    /**
     * Reads the raw YAML file and extracts inline comments (# ...) on property lines.
     * Matches comments to properties by finding the last key segment on each line.
     */
    private void attachComments(Path file, List<Property> properties) {
        try {
            var lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            // Build a map: last key segment → comment
            var commentMap = new HashMap<String, String>();
            for (var line : lines) {
                var commentIdx = findInlineComment(line);
                if (commentIdx < 0) continue;

                var comment = line.substring(commentIdx + 1).trim();
                if (comment.isEmpty()) continue;

                // Extract the key from the YAML line (before the colon)
                var beforeComment = line.substring(0, commentIdx);
                var colonIdx = beforeComment.indexOf(':');
                if (colonIdx < 0) continue;

                var keyPart = beforeComment.substring(0, colonIdx).trim();
                // keyPart could be "  port" or "    url" — last segment after indentation
                if (!keyPart.isEmpty()) {
                    commentMap.put(keyPart, comment);
                }
            }

            if (commentMap.isEmpty()) return;

            // Match comments to properties by last key segment
            for (int i = 0; i < properties.size(); i++) {
                var prop = properties.get(i);
                if (prop.hasComment()) continue;

                var lastSegment = lastKeySegment(prop.key());
                var comment = commentMap.get(lastSegment);
                if (comment != null) {
                    properties.set(i, prop.withComment(comment));
                }
            }
        } catch (IOException e) {
            // Ignore — comments are best-effort
        }
    }

    /** Find index of inline # comment, ignoring # inside quotes */
    private int findInlineComment(String line) {
        boolean inSingleQuote = false, inDoubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            var ch = line.charAt(i);
            if (ch == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (ch == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;
            else if (ch == '#' && !inSingleQuote && !inDoubleQuote) return i;
        }
        return -1;
    }

    private String lastKeySegment(String key) {
        var dotIdx = key.lastIndexOf('.');
        return dotIdx >= 0 ? key.substring(dotIdx + 1) : key;
    }

    public static String extractEnvName(Path file) {
        var fileName = file.getFileName().toString();
        var matcher = PROFILE_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return Environment.DEFAULT_ENV;
    }
}
