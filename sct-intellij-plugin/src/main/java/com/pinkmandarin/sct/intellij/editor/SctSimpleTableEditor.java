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
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

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
    private SectionTableModel tableModel;
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
        // === Toolbar ===
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));

        toolbar.add(new JLabel("Section:"));
        sectionCombo = new JComboBox<>(sections.toArray(String[]::new));
        sectionCombo.addActionListener(e -> refreshTable());
        toolbar.add(sectionCombo);

        toolbar.add(makeButton("Rename Section", e -> renameSection()));
        toolbar.add(makeButton("+ Section", e -> addSection()));
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(makeButton("+ Column", e -> addColumn()));
        toolbar.add(makeButton("- Column", e -> removeColumn()));
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(makeButton("+ Env", e -> addEnv()));
        toolbar.add(makeButton("- Env", e -> removeEnv()));
        toolbar.add(Box.createHorizontalStrut(8));

        var saveBtn = makeButton("💾 Save", e -> save());
        toolbar.add(saveBtn);
        toolbar.add(makeButton("🔄 Reload", e -> { loadFromFile(); updateSectionCombo(); refreshTable(); }));

        mainPanel.add(toolbar, BorderLayout.NORTH);

        // === Table ===
        tableModel = new SectionTableModel();
        table = new JBTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.setDefaultRenderer(Object.class, new ValueCellRenderer());

        // Double-click header to rename key
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int col = table.columnAtPoint(e.getPoint());
                    if (col > 0) renameKey(col);
                }
            }
        });

        mainPanel.add(new JBScrollPane(table), BorderLayout.CENTER);

        // === Status bar ===
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        refreshTable();
    }

    private JButton makeButton(String text, java.awt.event.ActionListener action) {
        var btn = new JButton(text);
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.addActionListener(action);
        return btn;
    }

    private void refreshTable() {
        var sel = (String) sectionCombo.getSelectedItem();
        if (sel == null && !sections.isEmpty()) sel = sections.get(0);
        if (sel == null) { tableModel.clear(); return; }
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
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 110 : 170);

        setStatus(keys.size() + " properties × " + environments.size() + " envs", false);
    }

    private void updateSectionCombo() {
        sectionCombo.setModel(new DefaultComboBoxModel<>(sections.toArray(String[]::new)));
    }

    // --- Actions ---

    private void renameSection() {
        var old = (String) sectionCombo.getSelectedItem();
        if (old == null) return;
        var name = JOptionPane.showInputDialog(mainPanel, "Rename section:", old);
        if (name == null || name.isBlank() || name.equals(old)) return;

        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            updated.add(p.section().equals(old)
                    ? new Property(name.trim(), p.key(), p.env(), p.value(), p.valueType()) : p);
        }
        allProperties = updated;
        sections.replaceAll(s -> s.equals(old) ? name.trim() : s);
        updateSectionCombo();
        sectionCombo.setSelectedItem(name.trim());
        setStatus("Section renamed (unsaved)", true);
    }

    private void addSection() {
        var name = JOptionPane.showInputDialog(mainPanel, "New section name:");
        if (name == null || name.isBlank()) return;
        if (sections.contains(name.trim())) { setStatus("Section already exists", true); return; }
        sections.add(name.trim());
        for (var env : environments) allProperties.add(Property.of(name.trim(), "key", env, ""));
        updateSectionCombo();
        sectionCombo.setSelectedItem(name.trim());
        setStatus("Section added (unsaved)", true);
    }

    private void addColumn() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var key = JOptionPane.showInputDialog(mainPanel, "New property key:");
        if (key == null || key.isBlank()) return;
        for (var env : environments) allProperties.add(Property.of(section, key.trim(), env, ""));
        refreshTable();
        setStatus("Column '" + key.trim() + "' added (unsaved)", true);
    }

    private void removeColumn() {
        var col = table.getSelectedColumn();
        if (col <= 0) { setStatus("Select a column first (click a data cell)", true); return; }
        var section = (String) sectionCombo.getSelectedItem();
        var key = tableModel.keys.get(col - 1);
        if (section == null) return;
        if (JOptionPane.showConfirmDialog(mainPanel, "Delete column '" + key + "'?",
                "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key));
        refreshTable();
        setStatus("Column '" + key + "' deleted (unsaved)", true);
    }

    private void renameKey(int col) {
        if (col <= 0) return;
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var oldKey = tableModel.keys.get(col - 1);
        var newKey = JOptionPane.showInputDialog(mainPanel, "Rename key:", oldKey);
        if (newKey == null || newKey.isBlank() || newKey.equals(oldKey)) return;

        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            updated.add(p.section().equals(section) && p.key().equals(oldKey)
                    ? new Property(p.section(), newKey.trim(), p.env(), p.value(), p.valueType()) : p);
        }
        allProperties = updated;
        refreshTable();
        setStatus("Key renamed: " + oldKey + " → " + newKey.trim() + " (unsaved)", true);
    }

    private void addEnv() {
        var name = JOptionPane.showInputDialog(mainPanel, "New environment name:");
        if (name == null || name.isBlank()) return;
        if (environments.contains(name.trim())) { setStatus("Environment already exists", true); return; }
        environments.add(name.trim());
        refreshTable();
        setStatus("Environment '" + name.trim() + "' added (unsaved)", true);
    }

    private void removeEnv() {
        var row = table.getSelectedRow();
        if (row < 0) { setStatus("Select a row first", true); return; }
        var env = tableModel.envNames.get(row);
        if (JOptionPane.showConfirmDialog(mainPanel, "Delete environment '" + env + "'?",
                "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        environments.remove(env);
        allProperties.removeIf(p -> p.env().equals(env));
        refreshTable();
        setStatus("Environment '" + env + "' deleted (unsaved)", true);
    }

    private void save() {
        try {
            new MasterMarkdownWriter().write(allProperties, Path.of(file.getPath()));
            file.refresh(false, false);
            setStatus("Saved!", false);
        } catch (IOException e) {
            LOG.warn("Save failed", e);
            setStatus("Save failed: " + e.getMessage(), true);
        }
    }

    private void setStatus(String text, boolean warning) {
        statusLabel.setText("  " + text);
        statusLabel.setForeground(warning
                ? JBColor.namedColor("SCT.warning", new Color(200, 150, 50))
                : JBColor.foreground());
    }

    // --- FileEditor ---

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

    // --- Table Model ---

    private class SectionTableModel extends AbstractTableModel {
        List<String> envNames = List.of();
        List<String> keys = List.of();
        String[][] data = new String[0][0];
        String section = "";

        void clear() {
            envNames = List.of(); keys = List.of(); data = new String[0][0]; section = "";
            fireTableStructureChanged();
        }

        void setData(List<String> envNames, List<String> keys, String[][] data, String section) {
            this.envNames = envNames; this.keys = keys; this.data = data; this.section = section;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return envNames.size(); }
        @Override public int getColumnCount() { return keys.size() + 1; }
        @Override public String getColumnName(int col) { return col == 0 ? "env" : keys.get(col - 1); }

        @Override public Object getValueAt(int row, int col) {
            if (col == 0) return Environment.DEFAULT_ENV.equals(envNames.get(row)) ? Environment.DEFAULT_DISPLAY : envNames.get(row);
            var val = data[row][col - 1];
            return val != null ? val : "";
        }

        @Override public boolean isCellEditable(int row, int col) { return col > 0; }

        @Override public void setValueAt(Object value, int row, int col) {
            if (col <= 0) return;
            var strValue = (String) value;
            data[row][col - 1] = strValue;
            var env = envNames.get(row);
            var key = keys.get(col - 1);
            allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));
            if (strValue != null && !strValue.isEmpty()) {
                allProperties.add("null".equals(strValue)
                        ? Property.of(section, key, env, null) : Property.of(section, key, env, strValue));
            }
            fireTableCellUpdated(row, col);
            setStatus("Unsaved changes", true);
        }
    }

    // --- Cell Renderer ---

    private static class ValueCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            var c = super.getTableCellRendererComponent(table, value, sel, focus, row, col);
            if (!sel && col > 0) {
                var text = value != null ? value.toString() : "";
                if (text.isEmpty()) {
                    c.setForeground(JBColor.GRAY);
                    setText("(inherit)");
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else if ("null".equals(text)) {
                    c.setForeground(new JBColor(new Color(180, 100, 100), new Color(200, 120, 120)));
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    c.setForeground(JBColor.foreground());
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
            } else if (col == 0) {
                setFont(getFont().deriveFont(Font.BOLD));
                c.setForeground(JBColor.foreground());
            } else {
                c.setForeground(JBColor.foreground());
                setFont(getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }
}
