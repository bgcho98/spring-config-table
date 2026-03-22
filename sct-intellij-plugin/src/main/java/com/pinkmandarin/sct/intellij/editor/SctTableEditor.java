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
 * Visual WYSIWYG table editor for SCT master Markdown files.
 * Features: section switching, cell editing, validation, auto-completion,
 * add/remove columns and environments.
 */
public class SctTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctTableEditor.class);

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
        table.setDefaultRenderer(Object.class, new ValidationCellRenderer());
        table.setDefaultEditor(Object.class, new AutoCompleteCellEditor());

        mainPanel.add(new JBScrollPane(table), BorderLayout.CENTER);

        // Status bar
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

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

        var data = new String[environments.size()][keys.size()];
        var types = new String[environments.size()][keys.size()];
        for (var prop : sectionProps) {
            int row = environments.indexOf(prop.env());
            int col = keys.indexOf(prop.key());
            if (row >= 0 && col >= 0) {
                data[row][col] = prop.isNullValue() ? "null" : prop.value();
                types[row][col] = prop.valueType();
            }
        }

        tableModel.setData(environments, keys, data, types, section);

        // Set column widths and header tooltips from Spring metadata
        var meta = SpringMetadataService.getInstance(project);
        var headerRenderer = new ColumnHeaderRenderer(meta, section);
        for (int i = 0; i < table.getColumnCount(); i++) {
            var column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(i == 0 ? 120 : 180);
            column.setHeaderRenderer(headerRenderer);
        }

        updateStatus();
    }

    private void updateStatus() {
        var warnings = validate();
        if (warnings.isEmpty()) {
            statusLabel.setText("  " + tableModel.keys.size() + " properties, " + environments.size() + " environments");
            statusLabel.setForeground(JBColor.foreground());
        } else {
            statusLabel.setText("  " + warnings.size() + " warning(s): " + warnings.get(0));
            statusLabel.setForeground(JBColor.namedColor("SCT.warningColor", new Color(200, 150, 50)));
        }
    }

    private List<String> validate() {
        var warnings = new ArrayList<String>();
        var section = tableModel.section;
        var defaultIdx = environments.indexOf(Environment.DEFAULT_ENV);

        for (int col = 0; col < tableModel.keys.size(); col++) {
            var key = tableModel.keys.get(col);
            var defaultVal = defaultIdx >= 0 ? tableModel.data[defaultIdx][col] : null;
            var defaultType = defaultIdx >= 0 ? tableModel.types[defaultIdx][col] : null;

            // Warning: no default value but profiles have values
            if ((defaultVal == null || defaultVal.isEmpty()) && defaultIdx >= 0) {
                for (int row = 0; row < environments.size(); row++) {
                    if (row == defaultIdx) continue;
                    var val = tableModel.data[row][col];
                    if (val != null && !val.isEmpty()) {
                        warnings.add(key + ": no default value but " + environments.get(row) + " has '" + val + "'");
                        break;
                    }
                }
            }

            // Warning: type mismatch between default and profiles
            if (defaultType != null && !"null".equals(defaultType)) {
                for (int row = 0; row < environments.size(); row++) {
                    if (row == defaultIdx) continue;
                    var val = tableModel.data[row][col];
                    var type = tableModel.types[row][col];
                    if (val != null && !val.isEmpty() && type != null && !type.equals(defaultType) && !"null".equals(type)) {
                        warnings.add(key + ": type mismatch — default is " + defaultType + " but " + environments.get(row) + " is " + type);
                    }
                }
            }
        }
        return warnings;
    }

    private void addColumn() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;

        // Combine Spring metadata properties + existing keys for suggestions
        var meta = SpringMetadataService.getInstance(project);
        var springKeys = meta.suggestProperties(section + ".");
        var existingKeys = allProperties.stream()
                .map(p -> p.section() + "." + p.key())
                .distinct()
                .sorted()
                .toList();

        var allSuggestions = new LinkedHashSet<String>();
        // Spring metadata keys with section prefix stripped
        for (var sk : springKeys) {
            if (sk.startsWith(section + ".")) {
                allSuggestions.add(sk.substring(section.length() + 1));
            }
        }
        // Existing keys from all sections
        for (var ek : existingKeys) {
            if (ek.startsWith(section + ".")) {
                allSuggestions.add(ek.substring(section.length() + 1));
            }
        }

        var combo = new JComboBox<>(allSuggestions.toArray(String[]::new));
        combo.setEditable(true);

        var panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JLabel("Property key (relative to " + section + "):"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        var result = JOptionPane.showConfirmDialog(mainPanel, panel, "Add Column", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        var key = ((String) combo.getSelectedItem());
        if (key == null || key.isBlank()) return;
        key = key.trim();

        for (var env : environments) {
            allProperties.add(Property.of(section, key, env, ""));
        }
        refreshTable();
    }

    private void removeColumn() {
        var col = table.getSelectedColumn();
        if (col <= 0) return;

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
        // Suggest common profile names
        var suggestions = new String[]{"dev", "alpha", "beta", "real", "dr", "gov", "local"};
        var combo = new JComboBox<>(suggestions);
        combo.setEditable(true);
        var result = JOptionPane.showConfirmDialog(mainPanel, combo, "New environment name", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        var envName = ((String) combo.getSelectedItem());
        if (envName == null || envName.isBlank()) return;
        envName = envName.trim();

        if (!environments.contains(envName)) {
            environments.add(envName);
        }
        refreshTable();
    }

    private void saveToFile() {
        try {
            var outputPath = Path.of(file.getPath());
            new MasterMarkdownWriter().write(allProperties, outputPath);
            file.refresh(false, false);
            statusLabel.setText("  Saved successfully.");
            statusLabel.setForeground(new JBColor(new Color(80, 150, 80), new Color(100, 180, 100)));
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
        String[][] types = new String[0][0];
        String section = "";

        void setData(List<String> envNames, List<String> keys, String[][] data, String[][] types, String section) {
            this.envNames = envNames;
            this.keys = keys;
            this.data = data;
            this.types = types;
            this.section = section;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return envNames.size(); }
        @Override public int getColumnCount() { return keys.size() + 1; }

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
            return col > 0;
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col <= 0) return;
            var strValue = (String) value;
            data[row][col - 1] = strValue;

            var env = envNames.get(row);
            var key = keys.get(col - 1);

            allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));

            if (strValue != null && !strValue.isEmpty()) {
                if ("null".equals(strValue)) {
                    allProperties.add(Property.of(section, key, env, null));
                } else {
                    allProperties.add(Property.of(section, key, env, strValue));
                }
            }

            // Update type for validation
            if (strValue != null && !strValue.isEmpty()) {
                var prop = allProperties.stream()
                        .filter(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env))
                        .findFirst();
                prop.ifPresent(p -> types[row][col - 1] = p.valueType());
            } else {
                types[row][col - 1] = null;
            }

            fireTableCellUpdated(row, col);
            updateStatus();
        }

        String getType(int row, int col) {
            if (col <= 0 || row < 0 || row >= types.length || col - 1 >= types[0].length) return null;
            return types[row][col - 1];
        }

        String getDefaultType(int col) {
            var defaultIdx = envNames.indexOf(Environment.DEFAULT_ENV);
            if (defaultIdx < 0 || col <= 0) return null;
            return types[defaultIdx][col - 1];
        }
    }

    // --- Cell Renderer with Validation ---

    private class ValidationCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int col) {
            var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);

            if (isSelected || col == 0) {
                c.setForeground(isSelected ? table.getSelectionForeground() : JBColor.foreground());
                c.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                return c;
            }

            var text = value != null ? value.toString() : "";
            c.setBackground(table.getBackground());

            if (text.isEmpty()) {
                c.setForeground(JBColor.GRAY);
                setText("(inherit)");
                return c;
            }

            if ("null".equals(text)) {
                c.setForeground(JBColor.namedColor("SCT.nullColor", new Color(180, 100, 100)));
                return c;
            }

            // Type mismatch validation
            var cellType = tableModel.getType(row, col);
            var defaultType = tableModel.getDefaultType(col);
            if (defaultType != null && cellType != null && !cellType.equals(defaultType)
                    && !"null".equals(cellType) && !"null".equals(defaultType)) {
                c.setBackground(JBColor.namedColor("SCT.typeMismatch", new Color(255, 245, 200)));
                setToolTipText("Type mismatch: expected " + defaultType + ", got " + cellType);
            } else {
                // Spring metadata tooltip
                var key = tableModel.section + "." + tableModel.keys.get(col - 1);
                var meta = SpringMetadataService.getInstance(project).getProperty(key);
                if (meta != null) {
                    var tip = meta.displayType();
                    if (meta.description() != null) tip += " — " + meta.description();
                    if (meta.defaultValue() != null) tip += " (default: " + meta.defaultValue() + ")";
                    setToolTipText(tip);
                } else {
                    setToolTipText(cellType != null ? "type: " + cellType : null);
                }
            }

            c.setForeground(JBColor.foreground());
            return c;
        }
    }

    // --- Column Header Renderer (unknown property = yellow) ---

    private static class ColumnHeaderRenderer extends DefaultTableCellRenderer {
        private final SpringMetadataService meta;
        private final String section;

        ColumnHeaderRenderer(SpringMetadataService meta, String section) {
            this.meta = meta;
            this.section = section;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int col) {
            var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            setBorder(UIManager.getBorder("TableHeader.cellBorder"));
            setHorizontalAlignment(CENTER);

            if (col == 0) {
                c.setBackground(UIManager.getColor("TableHeader.background"));
                setToolTipText(null);
                return c;
            }

            var fullKey = section + "." + value;
            var prop = meta.getProperty(fullKey);
            if (prop != null) {
                // Known Spring property
                c.setBackground(UIManager.getColor("TableHeader.background"));
                var tip = "<html><b>" + fullKey + "</b> (" + prop.displayType() + ")";
                if (prop.description() != null) tip += "<br>" + prop.description();
                if (prop.defaultValue() != null) tip += "<br>Default: " + prop.defaultValue();
                tip += "</html>";
                setToolTipText(tip);
            } else if (fullKey.startsWith("spring.") || fullKey.startsWith("server.")
                    || fullKey.startsWith("management.") || fullKey.startsWith("logging.")) {
                // Likely Spring property but not in metadata — yellow warning
                c.setBackground(JBColor.namedColor("SCT.unknownProperty", new Color(255, 250, 205)));
                setToolTipText("Unknown Spring property: " + fullKey);
            } else {
                // Custom application property — no warning
                c.setBackground(UIManager.getColor("TableHeader.background"));
                setToolTipText(fullKey);
            }

            return c;
        }
    }

    // --- Cell Editor with Auto-Complete ---

    private class AutoCompleteCellEditor extends DefaultCellEditor {

        AutoCompleteCellEditor() {
            super(new JTextField());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int col) {
            var field = (JTextField) super.getTableCellEditorComponent(table, value, isSelected, row, col);
            if (col <= 0) return field;

            var key = tableModel.keys.get(col - 1);
            var section = tableModel.section;
            var fullKey = section + "." + key;

            // Build tooltip with Spring metadata + other environment values
            var tips = new ArrayList<String>();

            var meta = SpringMetadataService.getInstance(project).getProperty(fullKey);
            if (meta != null) {
                tips.add("Type: " + meta.displayType());
                if (meta.defaultValue() != null) tips.add("Default: " + meta.defaultValue());
                if (meta.description() != null) tips.add(meta.description());
            }

            var otherValues = allProperties.stream()
                    .filter(p -> p.section().equals(section) && p.key().equals(key))
                    .map(p -> p.env() + "=" + (p.isNullValue() ? "null" : p.value()))
                    .filter(v -> !v.endsWith("="))
                    .toList();
            if (!otherValues.isEmpty()) {
                tips.add("Values: " + String.join(", ", otherValues));
            }

            if (!tips.isEmpty()) {
                field.setToolTipText("<html>" + String.join("<br>", tips) + "</html>");
            }

            return field;
        }
    }
}
