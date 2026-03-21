package com.pinkmandarin.sct.intellij;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.pinkmandarin.sct.core.exporter.YamlExporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;

import java.nio.file.Path;

public class SctGenerator {

    private static final String NOTIFICATION_GROUP = "sct";

    public static void generateAll(Project project) {
        var settings = SctSettings.getInstance(project);
        for (var mapping : settings.getMappings()) {
            generate(project, mapping);
        }
    }

    public static void generateByFilePath(Project project, String changedFilePath) {
        var basePath = project.getBasePath();
        if (basePath == null) return;

        var settings = SctSettings.getInstance(project);
        for (var mapping : settings.getMappings()) {
            if (mapping.masterFile == null || mapping.masterFile.isBlank()) continue;
            var masterPath = SctFileWatcher.normalizePath(Path.of(basePath, mapping.masterFile));
            if (masterPath.equals(changedFilePath)) {
                generate(project, mapping);
            }
        }
    }

    private static void generate(Project project, SctSettings.ModuleMapping mapping) {
        if (mapping.masterFile == null || mapping.masterFile.isBlank()) return;

        var basePath = project.getBasePath();
        if (basePath == null) {
            notify(project, SctBundle.message("notification.noBasePath"), NotificationType.WARNING);
            return;
        }

        var masterPath = Path.of(basePath, mapping.masterFile);
        var outputPath = Path.of(basePath, mapping.outputDir);

        if (!masterPath.toFile().exists()) {
            notify(project, SctBundle.message("notification.masterNotFound", masterPath), NotificationType.WARNING);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var result = new MasterMarkdownParser().parse(masterPath);
                new YamlExporter().exportAll(result.properties(), result.environments(), outputPath);

                ApplicationManager.getApplication().invokeLater(() ->
                        VirtualFileManager.getInstance().asyncRefresh(() ->
                                notify(project, SctBundle.message("notification.generated", result.environments().size()), NotificationType.INFORMATION)));
            } catch (Exception e) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SctGenerator.class).warn("YAML generation failed", e);
                notify(project, SctBundle.message("notification.generateFailed", e.getMessage()), NotificationType.ERROR);
            }
        });
    }

    private static void notify(Project project, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(SctBundle.message("notification.title"), content, type)
                .notify(project);
    }
}
