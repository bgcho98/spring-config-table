package com.pinkmandarin.sct.intellij.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class SpringMetadataService {

    private static final Logger LOG = Logger.getInstance(SpringMetadataService.class);
    private static final String METADATA_PATH = "META-INF/spring-configuration-metadata.json";

    private final Project project;
    private volatile Map<String, PropertyMeta> properties = Map.of();
    private volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 30_000;

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

    private void parseMetadataJson(String json, Map<String, PropertyMeta> result) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("properties")) return;

            var props = root.getAsJsonArray("properties");
            for (var element : props) {
                var obj = element.getAsJsonObject();
                var name = getStr(obj, "name");
                if (name == null) continue;

                result.put(name, new PropertyMeta(
                        name,
                        getStr(obj, "type"),
                        getStr(obj, "description"),
                        obj.has("defaultValue") ? obj.get("defaultValue").getAsString() : null
                ));
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse metadata JSON", e);
        }
    }

    private static @Nullable String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
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

        public String toValueType() {
            if (type == null) return "string";
            return switch (type) {
                case "java.lang.Boolean", "boolean" -> "bool";
                case "java.lang.Integer", "int", "java.lang.Long", "long" -> "int";
                case "java.lang.Double", "double", "java.lang.Float", "float" -> "float";
                default -> "string";
            };
        }
    }
}
