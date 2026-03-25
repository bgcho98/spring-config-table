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
    private JTextField lifecycleOrderField;
    private JTextField regionOrderField;

    public SctConfigurable(Project project) {
        this.project = project;
    }

    @Nls @Override
    public String getDisplayName() { return SctBundle.message("settings.displayName"); }

    @Nullable @Override
    public JComponent createComponent() {
        var settings = SctSettings.getInstance(project);
        var panel = new JPanel(new BorderLayout(0, 8));

        var topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        autoGenerateCheckBox = new JCheckBox(
                SctBundle.message("settings.autoGenerate"), settings.isAutoGenerate());
        autoGenerateCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(autoGenerateCheckBox);
        topPanel.add(Box.createVerticalStrut(8));

        // Lifecycle order
        topPanel.add(makeFieldRow(SctBundle.message("settings.lifecycleOrder"),
                lifecycleOrderField = new JTextField(settings.getLifecycleOrder()),
                SctBundle.message("settings.lifecycleOrder.tooltip")));
        topPanel.add(Box.createVerticalStrut(4));

        // Region order
        topPanel.add(makeFieldRow(SctBundle.message("settings.regionOrder"),
                regionOrderField = new JTextField(settings.getRegionOrder()),
                SctBundle.message("settings.regionOrder.tooltip")));
        topPanel.add(Box.createVerticalStrut(4));

        // Hint about embedded config
        var hintLabel = new JLabel(SctBundle.message("settings.envOrderHint"));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC, 11f));
        hintLabel.setForeground(com.intellij.ui.JBColor.GRAY);
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(hintLabel);
        topPanel.add(Box.createVerticalStrut(8));

        panel.add(topPanel, BorderLayout.NORTH);

        tableModel = new MappingTableModel(settings.getMappings());
        var table = new JBTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);

        var decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(b -> tableModel.addRow())
                .setRemoveAction(b -> { var row = table.getSelectedRow(); if (row >= 0) tableModel.removeRow(row); });

        panel.add(decorator.createPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel makeFieldRow(String label, JTextField field, String tooltip) {
        var row = new JPanel(new BorderLayout(4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        var lbl = new JLabel(label);
        lbl.setPreferredSize(new Dimension(130, 24));
        row.add(lbl, BorderLayout.WEST);
        field.setToolTipText(tooltip);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    @Override
    public boolean isModified() {
        if (autoGenerateCheckBox == null || tableModel == null) return false;
        var s = SctSettings.getInstance(project);
        if (autoGenerateCheckBox.isSelected() != s.isAutoGenerate()) return true;
        if (!Objects.equals(lifecycleOrderField.getText(), s.getLifecycleOrder())) return true;
        if (!Objects.equals(regionOrderField.getText(), s.getRegionOrder())) return true;
        var cur = s.getMappings(); var ed = tableModel.getMappings();
        if (cur.size() != ed.size()) return true;
        for (int i = 0; i < cur.size(); i++)
            if (!Objects.equals(cur.get(i).masterFile, ed.get(i).masterFile)
                    || !Objects.equals(cur.get(i).outputDir, ed.get(i).outputDir)) return true;
        return false;
    }

    @Override
    public void apply() {
        var s = SctSettings.getInstance(project);
        s.setAutoGenerate(autoGenerateCheckBox.isSelected());
        s.setMappings(tableModel.getMappings());
        s.setLifecycleOrder(lifecycleOrderField.getText());
        s.setRegionOrder(regionOrderField.getText());
        SctFileWatcher.getInstance(project).refreshCache();
    }

    @Override
    public void reset() {
        if (autoGenerateCheckBox == null || tableModel == null) return;
        var s = SctSettings.getInstance(project);
        autoGenerateCheckBox.setSelected(s.isAutoGenerate());
        tableModel.setMappings(s.getMappings());
        lifecycleOrderField.setText(s.getLifecycleOrder());
        regionOrderField.setText(s.getRegionOrder());
    }

    // --- Table Model (unchanged) ---

    private static class MappingTableModel extends AbstractTableModel {
        private String[] columns;
        private String[] getColumns() {
            if (columns == null) columns = new String[]{ SctBundle.message("settings.masterFileName"), SctBundle.message("settings.outputDir") };
            return columns;
        }
        private final List<SctSettings.ModuleMapping> mappings;
        MappingTableModel(List<SctSettings.ModuleMapping> src) { mappings = new ArrayList<>(); for (var m : src) mappings.add(m.copy()); }
        List<SctSettings.ModuleMapping> getMappings() { return mappings; }
        void setMappings(List<SctSettings.ModuleMapping> src) { mappings.clear(); for (var m : src) mappings.add(m.copy()); fireTableDataChanged(); }
        void addRow() { mappings.add(new SctSettings.ModuleMapping()); fireTableRowsInserted(mappings.size()-1, mappings.size()-1); }
        void removeRow(int row) { mappings.remove(row); fireTableRowsDeleted(row, row); }
        @Override public int getRowCount() { return mappings.size(); }
        @Override public int getColumnCount() { return getColumns().length; }
        @Override public String getColumnName(int col) { return getColumns()[col]; }
        @Override public boolean isCellEditable(int r, int c) { return true; }
        @Override public Object getValueAt(int r, int c) { var m=mappings.get(r); return c==0?m.masterFile:m.outputDir; }
        @Override public void setValueAt(Object v, int r, int c) { var m=mappings.get(r); if(c==0)m.masterFile=(String)v; else m.outputDir=(String)v; fireTableCellUpdated(r,c); }
    }
}
