package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefApp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Modeless dialog hosting the table editor.
 * Uses JCEF if available, falls back to JBTable.
 */
public class SctTableEditorDialog extends DialogWrapper {

    private final FileEditor editor;

    public SctTableEditorDialog(@NotNull Project project, @NotNull VirtualFile file) {
        super(project, false);
        if (JBCefApp.isSupported()) {
            this.editor = new SctTableEditor(project, file);
        } else {
            this.editor = new SctSimpleTableEditor(project, file);
        }
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
