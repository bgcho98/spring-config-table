package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans the project classpath for spring-configuration-metadata.json files
 * and provides property metadata for auto-completion and validation.
 */
@Service(Service.Level.PROJECT)
public final class SpringMetadataService {

    private static final Logger LOG = Logger.getInstance(SpringMetadataService.class);
    private static final String METADATA_PATH = "META-INF/spring-configuration-metadata.json";

    private final Project project;
    private volatile Map<String, PropertyMeta> properties = Map.of();
    private volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 30_000; // 30 seconds

    public SpringMetadataService(Project project) {
        this.project = project;
    }

    public static SpringMetadataService getInstance(@NotNull Project project) {
        return project.getService(SpringMetadataService.class);
    }

    public Map<String, PropertyMeta> getProperties() {
        if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
            refresh();
        }
        return properties;
    }

    public @Nullable PropertyMeta getProperty(String name) {
        return getProperties().get(name);
    }

    public boolean isKnownProperty(String name) {
        return getProperties().containsKey(name);
    }

    public List<String> suggestProperties(String prefix) {
        var props = getProperties();
        if (prefix == null || prefix.isEmpty()) {
            return props.keySet().stream().sorted().limit(100).toList();
        }
        return props.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .limit(50)
                .toList();
    }

    public synchronized void refresh() {
        var result = new ConcurrentHashMap<String, PropertyMeta>();

        try {
            var classesRoots = OrderEnumerator.orderEntries(project)
                    .withoutSdk()
                    .classes()
                    .getRoots();

            for (var root : classesRoots) {
                findAndParseMetadata(root, result);
            }

            // Also check compiled output directories
            for (var module : com.intellij.openapi.module.ModuleManager.getInstance(project).getModules()) {
                var outputPaths = ModuleRootManager.getInstance(module).orderEntries()
                        .withoutSdk().withoutLibraries().classes().getRoots();
                for (var outputRoot : outputPaths) {
                    findAndParseMetadata(outputRoot, result);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to scan Spring metadata", e);
        }

        properties = result;
        lastRefresh = System.currentTimeMillis();
        LOG.info("Spring metadata: loaded " + result.size() + " properties");
    }

    private void findAndParseMetadata(VirtualFile root, Map<String, PropertyMeta> result) {
        VirtualFile metaFile;

        if (root.isDirectory()) {
            metaFile = root.findFileByRelativePath(METADATA_PATH);
        } else {
            // JAR file
            var jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(root);
            if (jarRoot == null) return;
            metaFile = jarRoot.findFileByRelativePath(METADATA_PATH);
        }

        if (metaFile == null || !metaFile.isValid()) return;

        try {
            var content = new String(metaFile.contentsToByteArray(), StandardCharsets.UTF_8);
            parseMetadataJson(content, result);
        } catch (IOException e) {
            LOG.debug("Failed to read metadata from: " + root.getPath(), e);
        }
    }

    /**
     * Minimal JSON parser for spring-configuration-metadata.json.
     * Extracts "properties" array entries: name, type, description, defaultValue.
     * Uses simple string scanning to avoid adding a JSON library dependency.
     */
    private void parseMetadataJson(String json, Map<String, PropertyMeta> result) {
        // Find "properties" array
        var propsIdx = json.indexOf("\"properties\"");
        if (propsIdx < 0) return;

        var arrStart = json.indexOf('[', propsIdx);
        if (arrStart < 0) return;

        // Parse each object in the array
        int pos = arrStart + 1;
        while (pos < json.length()) {
            var objStart = json.indexOf('{', pos);
            if (objStart < 0) break;

            var objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;

            var obj = json.substring(objStart, objEnd + 1);
            var name = extractJsonString(obj, "name");
            if (name != null) {
                var type = extractJsonString(obj, "type");
                var desc = extractJsonString(obj, "description");
                var defaultValue = extractJsonValue(obj, "defaultValue");
                result.put(name, new PropertyMeta(name, type, desc, defaultValue));
            }

            pos = objEnd + 1;

            // Check if we've passed the end of the properties array
            var nextBracket = json.indexOf(']', pos);
            var nextBrace = json.indexOf('{', pos);
            if (nextBracket >= 0 && (nextBrace < 0 || nextBracket < nextBrace)) {
                break;
            }
        }
    }

    private int findMatchingBrace(String json, int openPos) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            var ch = json.charAt(i);
            if (ch == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private @Nullable String extractJsonString(String json, String key) {
        var pattern = "\"" + key + "\"";
        var idx = json.indexOf(pattern);
        if (idx < 0) return null;

        var colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;

        var valueStart = -1;
        for (int i = colonIdx + 1; i < json.length(); i++) {
            if (json.charAt(i) == '"') { valueStart = i + 1; break; }
            if (!Character.isWhitespace(json.charAt(i))) return null; // not a string value
        }
        if (valueStart < 0) return null;

        var sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            var ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return null;
    }

    private @Nullable String extractJsonValue(String json, String key) {
        var pattern = "\"" + key + "\"";
        var idx = json.indexOf(pattern);
        if (idx < 0) return null;

        var colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return null;

        var ch = json.charAt(valueStart);
        if (ch == '"') {
            return extractJsonString(json, key);
        }

        // Non-string value: read until , or } or ]
        var sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            var c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']') break;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public record PropertyMeta(
            String name,
            @Nullable String type,
            @Nullable String description,
            @Nullable String defaultValue
    ) {
        public String displayType() {
            if (type == null) return "unknown";
            var lastDot = type.lastIndexOf('.');
            return lastDot >= 0 ? type.substring(lastDot + 1) : type;
        }
    }
}
