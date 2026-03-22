package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Modeless dialog that hosts SctTableEditor as a standalone window.
 * Fallback when the FileEditorProvider tab doesn't appear.
 */
public class SctTableEditorDialog extends DialogWrapper {

    private final SctTableEditor editor;

    public SctTableEditorDialog(@NotNull Project project, @NotNull VirtualFile file) {
        super(project, false);
        this.editor = new SctTableEditor(project, file);
        setTitle("Table Editor — " + file.getName());
        setModal(false);
        init();
        getWindow().setMinimumSize(new Dimension(1100, 700));
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return editor.getComponent();
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected void dispose() {
        editor.dispose();
        super.dispose();
    }
}
