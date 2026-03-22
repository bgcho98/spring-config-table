package com.pinkmandarin.sct.intellij;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.*;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY;

/**
 * Converts selected YAML files to a master Markdown config table.
 * Appears in the Project View right-click menu.
 */
public class MigrateToMarkdownAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;

        var yamlFileList = YamlFileCollector.collect(e);
        if (yamlFileList.isEmpty()) return;
        var yamlFiles = yamlFileList.toArray(VirtualFile[]::new);

        // Ask user where to save the Markdown file
        var descriptor = new FileSaverDescriptor(
                SctBundle.message("migrate.saveTitle"), "", "md");
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        var result = wrapper.save("master-config.md");
        if (result == null) return;

        var outputPath = Path.of(result.getFile().getPath());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var importer = new YamlImporter();
                var allProperties = new ArrayList<Property>();
                var envNames = new LinkedHashSet<String>();
                envNames.add(Environment.DEFAULT_ENV);
                var defaultProperties = new HashMap<String, Property>();

                // Sort: default first, then profiles
                var sortedFiles = Arrays.stream(yamlFiles)
                        .sorted((a, b) -> {
                            var envA = YamlImporter.extractEnvName(Path.of(a.getName()));
                            var envB = YamlImporter.extractEnvName(Path.of(b.getName()));
                            if (Environment.DEFAULT_ENV.equals(envA)) return -1;
                            if (Environment.DEFAULT_ENV.equals(envB)) return 1;
                            return envA.compareTo(envB);
                        })
                        .toList();

                for (var file : sortedFiles) {
                    var envName = YamlImporter.extractEnvName(Path.of(file.getName()));
                    envNames.add(envName);
                    var props = importer.parseFile(Path.of(file.getPath()), envName);

                    if (Environment.DEFAULT_ENV.equals(envName)) {
                        allProperties.addAll(props);
                        for (var p : props) {
                            defaultProperties.put(p.section() + "." + p.key(), p);
                        }
                    } else {
                        // Filter out values identical to default
                        var filtered = props.stream()
                                .filter(p -> {
                                    var def = defaultProperties.get(p.section() + "." + p.key());
                                    return def == null || !Objects.equals(p.value(), def.value());
                                })
                                .toList();
                        allProperties.addAll(filtered);
                    }
                }

                new MasterMarkdownWriter().write(allProperties, outputPath);

                ApplicationManager.getApplication().invokeLater(() ->
                        VirtualFileManager.getInstance().asyncRefresh(() ->
                                notify(project, SctBundle.message("migrate.success", yamlFiles.length, outputPath.getFileName()),
                                        NotificationType.INFORMATION)));
            } catch (Exception ex) {
                com.intellij.openapi.diagnostic.Logger.getInstance(MigrateToMarkdownAction.class)
                        .warn("Migration failed", ex);
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
