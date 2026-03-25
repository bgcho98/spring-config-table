package com.pinkmandarin.sct.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads project-level SCT configuration from {@code .sct-config.yml}.
 *
 * <pre>
 * lifecycle-order: default, local, dev, alpha, beta, real
 * region-order: us, eu, ap
 * </pre>
 */
public record SctProjectConfig(
        List<String> lifecycleOrder,
        List<String> regionOrder
) {
    public static final String FILE_NAME = ".sct-config.yml";

    public static SctProjectConfig load(Path projectDir) {
        var configFile = projectDir.resolve(FILE_NAME);
        if (!Files.exists(configFile)) {
            return null;
        }
        try {
            var lines = Files.readAllLines(configFile);
            List<String> lifecycle = null;
            List<String> region = null;
            for (var line : lines) {
                var trimmed = line.trim();
                if (trimmed.startsWith("lifecycle-order:")) {
                    lifecycle = parseCommaList(trimmed.substring("lifecycle-order:".length()));
                } else if (trimmed.startsWith("region-order:")) {
                    region = parseCommaList(trimmed.substring("region-order:".length()));
                }
            }
            if (lifecycle == null && region == null) return null;
            return new SctProjectConfig(
                    lifecycle != null ? lifecycle : List.of(),
                    region != null ? region : List.of()
            );
        } catch (IOException e) {
            return null;
        }
    }

    public void save(Path projectDir) throws IOException {
        var sb = new StringBuilder();
        if (!lifecycleOrder.isEmpty()) {
            sb.append("lifecycle-order: ").append(String.join(", ", lifecycleOrder)).append("\n");
        }
        if (!regionOrder.isEmpty()) {
            sb.append("region-order: ").append(String.join(", ", regionOrder)).append("\n");
        }
        Files.writeString(projectDir.resolve(FILE_NAME), sb.toString());
    }

    private static List<String> parseCommaList(String value) {
        var result = new ArrayList<String>();
        for (var part : value.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
