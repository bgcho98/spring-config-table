package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.pinkmandarin.sct.intellij.editor.SctTableEditorDialog;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the SCT Table Editor for the selected markdown file.
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

        // Try to open and select the Table tab in the file editor
        var fem = FileEditorManager.getInstance(project);
        var editors = fem.openFile(mdFile, true);
        for (var editor : editors) {
            if ("Table".equals(editor.getName())) {
                fem.setSelectedEditor(mdFile, "sct-table-editor");
                return;
            }
        }

        // Fallback: modeless dialog
        new SctTableEditorDialog(project, mdFile).show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var files = YamlFileCollector.collect(e);
        boolean hasMd = files.stream().anyMatch(YamlFileCollector::isMarkdown);
        e.getPresentation().setEnabledAndVisible(hasMd);
    }
}
