package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SctConfigurable implements Configurable {

    private final Project project;
    private JCheckBox autoGenerateCheckBox;
    private MappingTableModel tableModel;
    private JTextField envOrderField;

    public SctConfigurable(Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return SctBundle.message("settings.displayName");
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        var settings = SctSettings.getInstance(project);

        var panel = new JPanel(new BorderLayout(0, 8));

        // Top options
        var topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        autoGenerateCheckBox = new JCheckBox(
                SctBundle.message("settings.autoGenerate"), settings.isAutoGenerate());
        autoGenerateCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(autoGenerateCheckBox);
        topPanel.add(Box.createVerticalStrut(8));

        // Environment order
        var envPanel = new JPanel(new BorderLayout(4, 0));
        envPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        envPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        envPanel.add(new JLabel(SctBundle.message("settings.envOrder")), BorderLayout.WEST);
        envOrderField = new JTextField(settings.getEnvOrder());
        envOrderField.setToolTipText("Comma-separated environment names in display order. Variants (e.g., gov-beta) auto-sort after their base.");
        envPanel.add(envOrderField, BorderLayout.CENTER);
        topPanel.add(envPanel);
        topPanel.add(Box.createVerticalStrut(4));

        panel.add(topPanel, BorderLayout.NORTH);

        // Module mappings table
        tableModel = new MappingTableModel(settings.getMappings());
        var table = new JBTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);

        var decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(b -> tableModel.addRow())
                .setRemoveAction(b -> {
                    var row = table.getSelectedRow();
                    if (row >= 0) tableModel.removeRow(row);
                });

        panel.add(decorator.createPanel(), BorderLayout.CENTER);

        return panel;
    }

    @Override
    public boolean isModified() {
        if (autoGenerateCheckBox == null || tableModel == null || envOrderField == null) return false;

        var settings = SctSettings.getInstance(project);
        if (autoGenerateCheckBox.isSelected() != settings.isAutoGenerate()) return true;
        if (!Objects.equals(envOrderField.getText(), settings.getEnvOrder())) return true;

        var current = settings.getMappings();
        var edited = tableModel.getMappings();
        if (current.size() != edited.size()) return true;
        for (int i = 0; i < current.size(); i++) {
            if (!Objects.equals(current.get(i).masterFile, edited.get(i).masterFile)
                    || !Objects.equals(current.get(i).outputDir, edited.get(i).outputDir)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() {
        var settings = SctSettings.getInstance(project);
        settings.setAutoGenerate(autoGenerateCheckBox.isSelected());
        settings.setMappings(tableModel.getMappings());
        settings.setEnvOrder(envOrderField.getText());
        SctFileWatcher.getInstance(project).refreshCache();
    }

    @Override
    public void reset() {
        if (autoGenerateCheckBox == null || tableModel == null || envOrderField == null) return;
        var settings = SctSettings.getInstance(project);
        autoGenerateCheckBox.setSelected(settings.isAutoGenerate());
        tableModel.setMappings(settings.getMappings());
        envOrderField.setText(settings.getEnvOrder());
    }

    private static class MappingTableModel extends AbstractTableModel {

        private String[] columns;

        private String[] getColumns() {
            if (columns == null) {
                columns = new String[]{
                        SctBundle.message("settings.masterFileName"),
                        SctBundle.message("settings.outputDir")
                };
            }
            return columns;
        }

        private final List<SctSettings.ModuleMapping> mappings;

        MappingTableModel(List<SctSettings.ModuleMapping> source) {
            this.mappings = new ArrayList<>();
            for (var m : source) mappings.add(m.copy());
        }

        List<SctSettings.ModuleMapping> getMappings() { return mappings; }

        void setMappings(List<SctSettings.ModuleMapping> source) {
            mappings.clear();
            for (var m : source) mappings.add(m.copy());
            fireTableDataChanged();
        }

        void addRow() {
            mappings.add(new SctSettings.ModuleMapping());
            fireTableRowsInserted(mappings.size() - 1, mappings.size() - 1);
        }

        void removeRow(int row) {
            mappings.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override public int getRowCount() { return mappings.size(); }
        @Override public int getColumnCount() { return getColumns().length; }
        @Override public String getColumnName(int col) { return getColumns()[col]; }
        @Override public boolean isCellEditable(int row, int col) { return true; }

        @Override
        public Object getValueAt(int row, int col) {
            var m = mappings.get(row);
            return col == 0 ? m.masterFile : m.outputDir;
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            var m = mappings.get(row);
            if (col == 0) m.masterFile = (String) value;
            else m.outputDir = (String) value;
            fireTableCellUpdated(row, col);
        }
    }
}
