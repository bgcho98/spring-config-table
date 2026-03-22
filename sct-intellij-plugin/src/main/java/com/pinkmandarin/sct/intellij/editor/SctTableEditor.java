package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Visual WYSIWYG table editor for SCT master Markdown files.
 * Sections shown in dropdown, table is editable, changes sync back to .md file.
 */
public class SctTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctTableEditor.class);

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;

    // Parsed data
    private List<Property> allProperties = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private List<String> environments = new ArrayList<>();

    // UI
    private JComboBox<String> sectionCombo;
    private JBTable table;
    private SectionTableModel tableModel;
    private boolean syncing = false;

    public SctTableEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;
        this.mainPanel = new JPanel(new BorderLayout());
        loadFromFile();
        buildUI();
    }

    private void loadFromFile() {
        try {
            var result = new MasterMarkdownParser().parse(Path.of(file.getPath()));
            allProperties = new ArrayList<>(result.properties());
            environments = result.environments().stream()
                    .map(Environment::name)
                    .collect(Collectors.toCollection(ArrayList::new));
            sections = allProperties.stream()
                    .map(Property::section)
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            LOG.warn("Failed to parse: " + file.getPath(), e);
        }
    }

    private void buildUI() {
        // Toolbar
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));

        sectionCombo = new JComboBox<>(sections.toArray(String[]::new));
        sectionCombo.addActionListener(e -> refreshTable());
        toolbar.add(new JLabel("Section:"));
        toolbar.add(sectionCombo);

        var addColBtn = new JButton("+ Column");
        addColBtn.addActionListener(e -> addColumn());
        toolbar.add(addColBtn);

        var removeColBtn = new JButton("- Column");
        removeColBtn.addActionListener(e -> removeColumn());
        toolbar.add(removeColBtn);

        var addEnvBtn = new JButton("+ Environment");
        addEnvBtn.addActionListener(e -> addEnvironment());
        toolbar.add(addEnvBtn);

        var saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveToFile());
        toolbar.add(saveBtn);

        mainPanel.add(toolbar, BorderLayout.NORTH);

        // Table
        tableModel = new SectionTableModel();
        table = new JBTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new ValueCellRenderer());

        mainPanel.add(new JBScrollPane(table), BorderLayout.CENTER);

        refreshTable();
    }

    private void refreshTable() {
        var selectedSection = (String) sectionCombo.getSelectedItem();
        if (selectedSection == null && !sections.isEmpty()) {
            selectedSection = sections.get(0);
        }
        if (selectedSection == null) return;
        final var section = selectedSection;

        var sectionProps = allProperties.stream()
                .filter(p -> p.section().equals(section))
                .toList();

        var keys = sectionProps.stream()
                .map(Property::key)
                .distinct()
                .sorted()
                .toList();

        // Build grid: rows = environments, columns = keys
        var data = new String[environments.size()][keys.size()];
        for (var prop : sectionProps) {
            int row = environments.indexOf(prop.env());
            int col = keys.indexOf(prop.key());
            if (row >= 0 && col >= 0) {
                data[row][col] = prop.isNullValue() ? "null" : prop.value();
            }
        }

        tableModel.setData(environments, keys, data, section);

        // Resize columns
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 120 : 180);
        }
    }

    private void addColumn() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;

        var key = JOptionPane.showInputDialog(mainPanel, "New property key:", "Add Column", JOptionPane.PLAIN_MESSAGE);
        if (key == null || key.isBlank()) return;

        // Add a default property for each environment
        for (var env : environments) {
            allProperties.add(Property.of(section, key.trim(), env, ""));
        }
        refreshTable();
    }

    private void removeColumn() {
        var col = table.getSelectedColumn();
        if (col <= 0) return; // can't remove env column

        var section = (String) sectionCombo.getSelectedItem();
        var key = tableModel.keys.get(col - 1);
        if (section == null) return;

        var confirm = JOptionPane.showConfirmDialog(mainPanel,
                "Remove column '" + key + "' from all environments?",
                "Remove Column", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key));
        refreshTable();
    }

    private void addEnvironment() {
        var env = JOptionPane.showInputDialog(mainPanel, "New environment name:", "Add Environment", JOptionPane.PLAIN_MESSAGE);
        if (env == null || env.isBlank()) return;

        var envName = env.trim();
        if (!environments.contains(envName)) {
            environments.add(envName);
        }
        refreshTable();
    }

    private void saveToFile() {
        try {
            // Rebuild properties from all sections
            var outputPath = Path.of(file.getPath());
            new MasterMarkdownWriter().write(allProperties, outputPath);
            file.refresh(false, false);
        } catch (IOException e) {
            LOG.warn("Failed to save: " + file.getPath(), e);
            JOptionPane.showMessageDialog(mainPanel, "Save failed: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- FileEditor interface ---

    @Override public @NotNull JComponent getComponent() { return mainPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return table; }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void dispose() {}
    @Override public @Nullable VirtualFile getFile() { return file; }

    // --- Table Model ---

    private class SectionTableModel extends AbstractTableModel {
        List<String> envNames = List.of();
        List<String> keys = List.of();
        String[][] data = new String[0][0];
        String section = "";

        void setData(List<String> envNames, List<String> keys, String[][] data, String section) {
            this.envNames = envNames;
            this.keys = keys;
            this.data = data;
            this.section = section;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return envNames.size(); }
        @Override public int getColumnCount() { return keys.size() + 1; } // +1 for env column

        @Override
        public String getColumnName(int col) {
            return col == 0 ? "env" : keys.get(col - 1);
        }

        @Override
        public Object getValueAt(int row, int col) {
            if (col == 0) {
                return Environment.DEFAULT_ENV.equals(envNames.get(row))
                        ? Environment.DEFAULT_DISPLAY : envNames.get(row);
            }
            var val = data[row][col - 1];
            return val != null ? val : "";
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > 0; // env column not editable
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col <= 0) return;
            var strValue = (String) value;
            data[row][col - 1] = strValue;

            var env = envNames.get(row);
            var key = keys.get(col - 1);

            // Update allProperties
            allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));

            if (strValue != null && !strValue.isEmpty()) {
                if ("null".equals(strValue)) {
                    allProperties.add(Property.of(section, key, env, null));
                } else {
                    allProperties.add(Property.of(section, key, env, strValue));
                }
            }

            fireTableCellUpdated(row, col);
        }
    }

    // --- Cell Renderer ---

    private static class ValueCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int col) {
            var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (!isSelected && col > 0) {
                var text = value != null ? value.toString() : "";
                if (text.isEmpty()) {
                    c.setForeground(JBColor.GRAY);
                    setText("(inherit)");
                } else if ("null".equals(text)) {
                    c.setForeground(JBColor.namedColor("SCT.nullColor", new Color(180, 100, 100)));
                } else {
                    c.setForeground(JBColor.foreground());
                }
            } else {
                c.setForeground(JBColor.foreground());
            }
            return c;
        }
    }
}
