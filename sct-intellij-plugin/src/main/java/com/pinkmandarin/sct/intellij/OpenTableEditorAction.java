package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.pinkmandarin.sct.intellij.editor.SctEditorProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the SCT Table Editor for the selected markdown file.
 * If the file is already open, navigates to the "Table" tab.
 */
public class OpenTableEditorAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;

        var files = YamlFileCollector.collect(e);
        var mdFile = files.stream()
                .filter(YamlFileCollector::isMarkdown)
                .findFirst()
                .orElse(null);
        if (mdFile == null) return;

        // Open file and try to select the Table editor tab
        var fem = FileEditorManager.getInstance(project);
        var editors = fem.openFile(mdFile, true);

        // Find and select our SctTableEditor tab
        for (var editor : editors) {
            if ("Table".equals(editor.getName())) {
                fem.setSelectedEditor(mdFile, "sct-table-editor");
                return;
            }
        }

        // If Table tab doesn't exist (editor provider not triggered),
        // open in a modeless dialog as fallback
        openAsDialog(project, mdFile);
    }

    private void openAsDialog(com.intellij.openapi.project.Project project, VirtualFile file) {
        try {
            var parser = new com.pinkmandarin.sct.core.master.MasterMarkdownParser();
            var result = parser.parse(java.nio.file.Path.of(file.getPath()));

            var rows = new java.util.ArrayList<YamlLensAction.PropertyRow>();
            for (var prop : result.properties()) {
                var fullKey = prop.section() + "." + prop.key();
                var value = prop.isNullValue() ? "null" : (prop.value() != null ? prop.value() : "");
                rows.add(new YamlLensAction.PropertyRow(fullKey, prop.env(), value, file.getPath()));
            }

            var profiles = rows.stream()
                    .map(r -> r.profile()).distinct()
                    .sorted((a, b) -> "default".equals(a) ? -1 : "default".equals(b) ? 1 : a.compareTo(b))
                    .toList();

            // Reuse YAML Lens modeless dialog
            new com.pinkmandarin.sct.intellij.editor.SctTableEditorDialog(project, file).show();
        } catch (Exception ex) {
            com.intellij.openapi.diagnostic.Logger.getInstance(OpenTableEditorAction.class)
                    .warn("Failed to open table editor", ex);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var files = YamlFileCollector.collect(e);
        boolean hasMd = files.stream().anyMatch(YamlFileCollector::isMarkdown);
        e.getPresentation().setEnabledAndVisible(hasMd);
    }
}
