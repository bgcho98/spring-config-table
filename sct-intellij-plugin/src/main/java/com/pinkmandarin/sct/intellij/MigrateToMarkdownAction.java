package com.pinkmandarin.sct.intellij;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.pinkmandarin.sct.core.config.SctProjectConfig;
import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Converts selected YAML files to a master Markdown config table.
 * Delegates to YamlImporter.importFiles() for deduplication logic.
 */
public class MigrateToMarkdownAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(MigrateToMarkdownAction.class);

    @SuppressWarnings("deprecation") // FileSaverDescriptor constructor deprecated in 2026.1, no replacement in 2024.3
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;

        var yamlFiles = YamlFileCollector.collect(e).stream()
                .filter(YamlFileCollector::isYaml)
                .toList();
        if (yamlFiles.isEmpty()) return;

        var descriptor = new FileSaverDescriptor(
                SctBundle.message("migrate.saveTitle"), "");
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        var baseDir = ProjectUtil.guessProjectDir(project);
        var result = wrapper.save(baseDir, "master-config.md");
        if (result == null) return;

        var outputPath = Path.of(result.getFile().getPath());
        var filePaths = yamlFiles.stream().map(f -> Path.of(f.getPath())).toList();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var parseResult = new YamlImporter().importFiles(filePaths);
                var basePath = project.getBasePath();
                var projectConfig = basePath != null
                        ? SctProjectConfig.load(Path.of(basePath)) : null;
                var sctS = SctSettings.getInstance(project);
                var lifecycle = projectConfig != null
                        ? projectConfig.lifecycleOrder() : sctS.getLifecycleOrderList();
                var region = projectConfig != null
                        ? projectConfig.regionOrder() : sctS.getRegionOrderList();
                new MasterMarkdownWriter()
                        .withEnvOrder(lifecycle, region)
                        .write(parseResult.properties(), outputPath);

                // Auto-create .sct-config.yml if it doesn't exist
                if (basePath != null && projectConfig == null) {
                    new SctProjectConfig(lifecycle, region).save(Path.of(basePath));
                }

                ApplicationManager.getApplication().invokeLater(() ->
                        WriteAction.run(() ->
                                VirtualFileManager.getInstance().asyncRefresh(() ->
                                        notify(project, SctBundle.message("migrate.success", yamlFiles.size(), outputPath.getFileName()),
                                                NotificationType.INFORMATION))));
            } catch (Exception ex) {
                LOG.warn("Migration failed", ex);
                notify(project, SctBundle.message("migrate.failed", ex.getMessage()), NotificationType.ERROR);
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(true);
    }

    private static void notify(com.intellij.openapi.project.Project project, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("sct")
                .createNotification(SctBundle.message("notification.title"), content, type)
                .notify(project);
    }
}
