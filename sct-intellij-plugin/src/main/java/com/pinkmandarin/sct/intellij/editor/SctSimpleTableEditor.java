package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
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
 * JBTable-based fallback editor when JCEF is not available.
 */
public class SctSimpleTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctSimpleTableEditor.class);

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;

    private List<Property> allProperties = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private List<String> environments = new ArrayList<>();

    private JComboBox<String> sectionCombo;
    private JBTable table;
    private SimpleTableModel tableModel;
    private JLabel statusLabel;

    public SctSimpleTableEditor(@NotNull Project project, @NotNull VirtualFile file) {
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
                    .map(Environment::name).collect(Collectors.toCollection(ArrayList::new));
            sections = allProperties.stream()
                    .map(Property::section).distinct().collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            LOG.warn("Failed to parse: " + file.getPath(), e);
        }
    }

    private void buildUI() {
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

        var addEnvBtn = new JButton("+ Env");
        addEnvBtn.addActionListener(e -> addEnv());
        toolbar.add(addEnvBtn);

        var saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> save());
        toolbar.add(saveBtn);

        var reloadBtn = new JButton("Reload");
        reloadBtn.addActionListener(e -> { loadFromFile(); sectionCombo.setModel(new DefaultComboBoxModel<>(sections.toArray(String[]::new))); refreshTable(); });
        toolbar.add(reloadBtn);

        mainPanel.add(toolbar, BorderLayout.NORTH);

        tableModel = new SimpleTableModel();
        table = new JBTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        mainPanel.add(new JBScrollPane(table), BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        refreshTable();
    }

    private void refreshTable() {
        var sel = (String) sectionCombo.getSelectedItem();
        if (sel == null && !sections.isEmpty()) sel = sections.get(0);
        if (sel == null) return;
        final var section = sel;

        var sectionProps = allProperties.stream().filter(p -> p.section().equals(section)).toList();
        var keys = sectionProps.stream().map(Property::key).distinct().sorted().toList();

        var data = new String[environments.size()][keys.size()];
        for (var prop : sectionProps) {
            int row = environments.indexOf(prop.env());
            int col = keys.indexOf(prop.key());
            if (row >= 0 && col >= 0) data[row][col] = prop.isNullValue() ? "null" : prop.value();
        }
        tableModel.setData(environments, keys, data, section);
        for (int i = 0; i < table.getColumnCount(); i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 120 : 180);
        statusLabel.setText("  " + keys.size() + " properties, " + environments.size() + " environments");
    }

    private void addColumn() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var key = JOptionPane.showInputDialog(mainPanel, "Property key:");
        if (key == null || key.isBlank()) return;
        for (var env : environments) allProperties.add(Property.of(section, key.trim(), env, ""));
        refreshTable();
    }

    private void removeColumn() {
        var col = table.getSelectedColumn();
        if (col <= 0) return;
        var section = (String) sectionCombo.getSelectedItem();
        var key = tableModel.keys.get(col - 1);
        if (section == null) return;
        if (JOptionPane.showConfirmDialog(mainPanel, "Delete '" + key + "'?") != JOptionPane.YES_OPTION) return;
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key));
        refreshTable();
    }

    private void addEnv() {
        var env = JOptionPane.showInputDialog(mainPanel, "Environment name:");
        if (env == null || env.isBlank()) return;
        if (!environments.contains(env.trim())) environments.add(env.trim());
        refreshTable();
    }

    private void save() {
        try {
            new MasterMarkdownWriter().write(allProperties, Path.of(file.getPath()));
            file.refresh(false, false);
            statusLabel.setText("  Saved.");
            statusLabel.setForeground(new JBColor(new Color(80, 150, 80), new Color(100, 180, 100)));
        } catch (IOException e) {
            LOG.warn("Save failed", e);
            statusLabel.setText("  Save failed: " + e.getMessage());
            statusLabel.setForeground(JBColor.RED);
        }
    }

    @Override public @NotNull JComponent getComponent() { return mainPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return table; }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void dispose() {}
    @Override public @Nullable VirtualFile getFile() { return file; }

    private class SimpleTableModel extends AbstractTableModel {
        List<String> envNames = List.of();
        List<String> keys = List.of();
        String[][] data = new String[0][0];
        String section = "";

        void setData(List<String> envNames, List<String> keys, String[][] data, String section) {
            this.envNames = envNames; this.keys = keys; this.data = data; this.section = section;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return envNames.size(); }
        @Override public int getColumnCount() { return keys.size() + 1; }
        @Override public String getColumnName(int col) { return col == 0 ? "env" : keys.get(col - 1); }

        @Override public Object getValueAt(int row, int col) {
            if (col == 0) return Environment.DEFAULT_ENV.equals(envNames.get(row)) ? Environment.DEFAULT_DISPLAY : envNames.get(row);
            var val = data[row][col - 1]; return val != null ? val : "";
        }

        @Override public boolean isCellEditable(int row, int col) { return col > 0; }

        @Override public void setValueAt(Object value, int row, int col) {
            if (col <= 0) return;
            var strValue = (String) value; data[row][col - 1] = strValue;
            var env = envNames.get(row); var key = keys.get(col - 1);
            allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));
            if (strValue != null && !strValue.isEmpty())
                allProperties.add("null".equals(strValue) ? Property.of(section, key, env, null) : Property.of(section, key, env, strValue));
            fireTableCellUpdated(row, col);
        }
    }

    private static class CellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            var c = super.getTableCellRendererComponent(table, value, sel, focus, row, col);
            if (!sel && col > 0) {
                var text = value != null ? value.toString() : "";
                if (text.isEmpty()) { c.setForeground(JBColor.GRAY); setText("(inherit)"); }
                else if ("null".equals(text)) { c.setForeground(new JBColor(new Color(180,100,100), new Color(200,120,120))); }
                else { c.setForeground(JBColor.foreground()); }
            } else { c.setForeground(JBColor.foreground()); }
            return c;
        }
    }
}
