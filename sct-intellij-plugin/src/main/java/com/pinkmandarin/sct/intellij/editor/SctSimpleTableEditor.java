package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Master-Detail editor for SCT master Markdown files.
 * Left panel: searchable property list. Right panel: environment values for selected property.
 */
public class SctSimpleTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctSimpleTableEditor.class);

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;

    private List<Property> allProperties = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private List<String> environments = new ArrayList<>();

    // UI components
    private JComboBox<String> sectionCombo;
    private SearchTextField searchField;
    private JBList<String> propertyList;
    private DefaultListModel<String> listModel;
    private JPanel detailPanel;
    private JLabel statusLabel;
    private JLabel detailTitle;
    private final Map<String, JTextField> envFields = new LinkedHashMap<>();

    private List<String> currentKeys = List.of(); // keys for current section
    private String selectedKey = null;

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
        // === Top toolbar ===
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        toolbar.add(new JLabel("Section:"));
        sectionCombo = new JComboBox<>(sections.toArray(String[]::new));
        sectionCombo.addActionListener(e -> onSectionChanged());
        toolbar.add(sectionCombo);
        toolbar.add(btn("Rename", e -> renameSection()));
        toolbar.add(btn("+ Section", e -> addSection()));
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(btn("💾 Save", e -> save()));
        toolbar.add(btn("🔄 Reload", e -> { loadFromFile(); updateSectionCombo(); onSectionChanged(); }));
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(btn("+ Env", e -> addEnv()));
        toolbar.add(btn("- Env", e -> removeEnv()));
        mainPanel.add(toolbar, BorderLayout.NORTH);

        // === Left panel: search + property list ===
        var leftPanel = new JPanel(new BorderLayout(0, 4));
        leftPanel.setPreferredSize(new Dimension(260, 0));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 0));

        searchField = new SearchTextField(false);
        searchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { filterPropertyList(); }
        });
        leftPanel.add(searchField, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        propertyList = new JBList<>(listModel);
        propertyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        propertyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onPropertySelected();
        });
        propertyList.setCellRenderer(new PropertyListRenderer());
        leftPanel.add(new JBScrollPane(propertyList), BorderLayout.CENTER);

        var leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        leftButtons.add(btn("+ Property", e -> addProperty()));
        leftButtons.add(btn("Rename", e -> renameProperty()));
        leftButtons.add(btn("Delete", e -> deleteProperty()));
        leftPanel.add(leftButtons, BorderLayout.SOUTH);

        // === Right panel: detail form ===
        detailPanel = new JPanel();
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));

        detailTitle = new JLabel(" ");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 14f));
        detailTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailTitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));

        var rightWrapper = new JPanel(new BorderLayout());
        rightWrapper.add(detailTitle, BorderLayout.NORTH);
        rightWrapper.add(new JBScrollPane(detailPanel), BorderLayout.CENTER);

        // === Split pane ===
        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightWrapper);
        splitPane.setDividerLocation(260);
        splitPane.setContinuousLayout(true);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // === Status bar ===
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        onSectionChanged();
    }

    private JButton btn(String text, java.awt.event.ActionListener action) {
        var b = new JButton(text);
        b.setFont(b.getFont().deriveFont(11f));
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(action);
        return b;
    }

    // === Event handlers ===

    private void onSectionChanged() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) { currentKeys = List.of(); listModel.clear(); return; }

        currentKeys = allProperties.stream()
                .filter(p -> p.section().equals(section))
                .map(Property::key).distinct().sorted().toList();
        filterPropertyList();
        setStatus(currentKeys.size() + " properties × " + environments.size() + " envs", false);
    }

    private void filterPropertyList() {
        var filter = searchField.getText().toLowerCase();
        listModel.clear();
        for (var key : currentKeys) {
            if (filter.isEmpty() || key.toLowerCase().contains(filter)) {
                listModel.addElement(key);
            }
        }
        if (listModel.size() > 0) {
            propertyList.setSelectedIndex(0);
        } else {
            onPropertySelected();
        }
    }

    private void onPropertySelected() {
        selectedKey = propertyList.getSelectedValue();
        detailPanel.removeAll();
        envFields.clear();

        if (selectedKey == null) {
            detailTitle.setText("Select a property");
            detailPanel.revalidate();
            detailPanel.repaint();
            return;
        }

        var section = (String) sectionCombo.getSelectedItem();
        detailTitle.setText(section + "." + selectedKey);

        for (var env : environments) {
            var displayEnv = Environment.DEFAULT_ENV.equals(env) ? Environment.DEFAULT_DISPLAY : env;

            var prop = allProperties.stream()
                    .filter(p -> p.section().equals(section) && p.key().equals(selectedKey) && p.env().equals(env))
                    .findFirst();
            var value = prop.map(p -> p.isNullValue() ? "null" : p.value()).orElse("");

            var row = new JPanel(new BorderLayout(8, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

            var label = new JLabel(displayEnv);
            label.setPreferredSize(new Dimension(120, 24));
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            if (Environment.DEFAULT_ENV.equals(env)) {
                label.setForeground(JBColor.namedColor("SCT.defaultEnv", new Color(100, 160, 255)));
            }
            row.add(label, BorderLayout.WEST);

            var field = new JTextField(value);
            field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            if (value.isEmpty()) {
                field.putClientProperty("JTextField.placeholderText", "(inherit from default)");
            }
            // Save on focus lost
            final var envName = env;
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    onFieldChanged(section, selectedKey, envName, field.getText());
                }
            });
            row.add(field, BorderLayout.CENTER);
            envFields.put(env, field);

            detailPanel.add(row);
            detailPanel.add(Box.createVerticalStrut(4));
        }

        detailPanel.add(Box.createVerticalGlue());
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void onFieldChanged(String section, String key, String env, String value) {
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));
        if (value != null && !value.isEmpty()) {
            allProperties.add("null".equals(value)
                    ? Property.of(section, key, env, null) : Property.of(section, key, env, value));
        }
        setStatus("Unsaved changes", true);
    }

    // === Actions ===

    private void addProperty() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var key = JOptionPane.showInputDialog(mainPanel, "New property key:");
        if (key == null || key.isBlank()) return;
        for (var env : environments) allProperties.add(Property.of(section, key.trim(), env, ""));
        onSectionChanged();
        propertyList.setSelectedValue(key.trim(), true);
        setStatus("Property added (unsaved)", true);
    }

    private void renameProperty() {
        if (selectedKey == null) return;
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var newKey = JOptionPane.showInputDialog(mainPanel, "Rename property:", selectedKey);
        if (newKey == null || newKey.isBlank() || newKey.equals(selectedKey)) return;

        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            updated.add(p.section().equals(section) && p.key().equals(selectedKey)
                    ? new Property(p.section(), newKey.trim(), p.env(), p.value(), p.valueType()) : p);
        }
        allProperties = updated;
        onSectionChanged();
        propertyList.setSelectedValue(newKey.trim(), true);
        setStatus("Property renamed (unsaved)", true);
    }

    private void deleteProperty() {
        if (selectedKey == null) return;
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        if (JOptionPane.showConfirmDialog(mainPanel, "Delete '" + selectedKey + "'?",
                "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(selectedKey));
        onSectionChanged();
        setStatus("Property deleted (unsaved)", true);
    }

    private void renameSection() {
        var old = (String) sectionCombo.getSelectedItem();
        if (old == null) return;
        var name = JOptionPane.showInputDialog(mainPanel, "Rename section:", old);
        if (name == null || name.isBlank() || name.equals(old)) return;
        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            updated.add(p.section().equals(old) ? new Property(name.trim(), p.key(), p.env(), p.value(), p.valueType()) : p);
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

    private void addEnv() {
        var name = JOptionPane.showInputDialog(mainPanel, "New environment name:");
        if (name == null || name.isBlank()) return;
        if (environments.contains(name.trim())) { setStatus("Environment already exists", true); return; }
        environments.add(name.trim());
        onPropertySelected(); // refresh detail
        setStatus("Environment '" + name.trim() + "' added (unsaved)", true);
    }

    private void removeEnv() {
        var envs = environments.toArray(String[]::new);
        var env = (String) JOptionPane.showInputDialog(mainPanel, "Select environment to delete:",
                "Delete Environment", JOptionPane.QUESTION_MESSAGE, null, envs, null);
        if (env == null) return;
        environments.remove(env);
        allProperties.removeIf(p -> p.env().equals(env));
        onPropertySelected();
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

    private void updateSectionCombo() {
        sectionCombo.setModel(new DefaultComboBoxModel<>(sections.toArray(String[]::new)));
    }

    private void setStatus(String text, boolean warning) {
        statusLabel.setText("  " + text);
        statusLabel.setForeground(warning
                ? JBColor.namedColor("SCT.warning", new Color(200, 150, 50))
                : JBColor.foreground());
    }

    // === Property list renderer ===

    private class PropertyListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            var c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            var key = (String) value;
            var section = (String) sectionCombo.getSelectedItem();
            if (section != null && key != null) {
                // Check if this property has values in non-default envs
                var hasOverrides = allProperties.stream()
                        .anyMatch(p -> p.section().equals(section) && p.key().equals(key)
                                && !p.env().equals(Environment.DEFAULT_ENV)
                                && p.value() != null && !p.value().isEmpty());
                if (hasOverrides) {
                    setText("● " + key);
                } else {
                    setText("  " + key);
                }
            }
            setFont(getFont().deriveFont(12f));
            return c;
        }
    }

    // === FileEditor interface ===

    @Override public @NotNull JComponent getComponent() { return mainPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return searchField; }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void dispose() {}
    @Override public @Nullable VirtualFile getFile() { return file; }
}
