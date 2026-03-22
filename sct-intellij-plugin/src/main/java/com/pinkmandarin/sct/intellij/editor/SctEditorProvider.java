package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Provides a visual table editor for SCT master Markdown files.
 * Adds a "Table" tab alongside the default text editor.
 */
public class SctEditorProvider implements FileEditorProvider, DumbAware {

    private static final String EDITOR_TYPE_ID = "sct-table-editor";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        if (!file.getName().endsWith(".md")) return false;
        // Quick check: file should contain markdown table markers
        try {
            var content = new String(file.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return content.contains("## ") && content.contains("| env |");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new SctTableEditor(project, file);
    }

    @Override
    public @NotNull @NonNls String getEditorTypeId() {
        return EDITOR_TYPE_ID;
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR;
    }
}
