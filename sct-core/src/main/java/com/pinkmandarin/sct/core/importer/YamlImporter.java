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

        var envs = new ArrayList<Environment>();
        var allProperties = new ArrayList<Property>();
        var defaultProperties = new HashMap<String, Property>();

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

        // Step 2: parse profile files, removing values identical to default
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
    List<Property> parseFile(Path file, String envName) throws IOException {
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

    static String extractEnvName(Path file) {
        var fileName = file.getFileName().toString();
        var matcher = PROFILE_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return Environment.DEFAULT_ENV;
    }
}
