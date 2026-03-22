package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class SctFileWatcher {

    private final Project project;
    private volatile Set<String> cachedMasterPaths = Set.of();

    public SctFileWatcher(Project project) {
        this.project = project;
    }

    public static SctFileWatcher getInstance(@NotNull Project project) {
        return project.getService(SctFileWatcher.class);
    }

    public void refreshCache() {
        var basePath = project.getBasePath();
        if (basePath == null) {
            cachedMasterPaths = Set.of();
            return;
        }
        cachedMasterPaths = SctSettings.getInstance(project).getMappings().stream()
                .filter(m -> m.masterFile != null && !m.masterFile.isBlank())
                .map(m -> normalizePath(Path.of(basePath, m.masterFile)))
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
    }

    public Set<String> getMasterPaths() {
        return cachedMasterPaths;
    }

    /**
     * Normalizes a path to use forward slashes (matching IntelliJ VirtualFile.getPath()).
     */
    static String normalizePath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
