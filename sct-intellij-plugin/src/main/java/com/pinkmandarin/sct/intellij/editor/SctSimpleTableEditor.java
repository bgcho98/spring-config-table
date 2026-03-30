package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
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
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class SctSimpleTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctSimpleTableEditor.class);
    private static final int GROUP_THRESHOLD = 10;

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;

    private List<Property> allProperties = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private List<String> environments = new ArrayList<>();

    private JComboBox<String> sectionCombo;
    private SearchTextField searchField;
    private JBList<GroupedItem> propertyList;
    private DefaultListModel<GroupedItem> listModel;
    private JPanel detailPanel;
    private JLabel statusLabel;
    private JLabel detailTitle;

    private List<String> currentKeys = List.of();
    private String selectedKey = null;

    // === Grouped Item types ===

    sealed interface GroupedItem {
        record Header(String groupName, int count) implements GroupedItem {}
        record Entry(String fullKey, String displayKey, String groupName) implements GroupedItem {}
    }

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
            var envOrder = resolveEnvOrder();
            var envComparator = Environment.comparator(envOrder.lifecycle, envOrder.region);
            environments = result.environments().stream()
                    .map(Environment::name)
                    .sorted(envComparator)
                    .collect(Collectors.toCollection(ArrayList::new));
            sections = allProperties.stream()
                    .map(Property::section).distinct().collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            LOG.warn("Failed to parse: " + file.getPath(), e);
        }
    }

    private void buildUI() {
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

        // Left panel
        var leftPanel = new JPanel(new BorderLayout(0, 4));
        leftPanel.setPreferredSize(new Dimension(280, 0));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 0));

        searchField = new SearchTextField(false);
        searchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { filterPropertyList(); }
        });
        leftPanel.add(searchField, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        propertyList = new JBList<>(listModel);
        propertyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        propertyList.setCellRenderer(new GroupedListRenderer());
        propertyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                var sel = propertyList.getSelectedValue();
                // Skip group headers — move to next entry
                if (sel instanceof GroupedItem.Header) {
                    var idx = propertyList.getSelectedIndex();
                    for (int i = idx + 1; i < listModel.size(); i++) {
                        if (listModel.get(i) instanceof GroupedItem.Entry) {
                            propertyList.setSelectedIndex(i);
                            return;
                        }
                    }
                    return;
                }
                onPropertySelected();
            }
        });
        leftPanel.add(new JBScrollPane(propertyList), BorderLayout.CENTER);

        var leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        leftButtons.add(btn("+ Property", e -> addProperty()));
        leftButtons.add(btn("Rename", e -> renameProperty()));
        leftButtons.add(btn("Delete", e -> deleteProperty()));
        leftPanel.add(leftButtons, BorderLayout.SOUTH);

        // Right panel
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

        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightWrapper);
        splitPane.setDividerLocation(280);
        splitPane.setContinuousLayout(true);
        mainPanel.add(splitPane, BorderLayout.CENTER);

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

    // === Grouping logic ===

    private List<GroupedItem> buildGroupedItems(List<String> keys) {
        if (keys.size() <= GROUP_THRESHOLD) {
            // No grouping needed — flat list
            return keys.stream()
                    .map(k -> (GroupedItem) new GroupedItem.Entry(k, k, ""))
                    .toList();
        }

        // Group by first dot segment
        var groups = new LinkedHashMap<String, List<String>>();
        for (var key : keys) {
            var dotIdx = key.indexOf('.');
            var group = dotIdx > 0 ? key.substring(0, dotIdx) : "(root)";
            groups.computeIfAbsent(group, g -> new ArrayList<>()).add(key);
        }

        var result = new ArrayList<GroupedItem>();
        for (var entry : groups.entrySet()) {
            var groupName = entry.getKey();
            var groupKeys = entry.getValue();
            result.add(new GroupedItem.Header(groupName, groupKeys.size()));
            for (var key : groupKeys) {
                // Strip group prefix for display
                var displayKey = key;
                if (!"(root)".equals(groupName) && key.startsWith(groupName + ".")) {
                    displayKey = key.substring(groupName.length() + 1);
                }
                result.add(new GroupedItem.Entry(key, displayKey, groupName));
            }
        }
        return result;
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

        List<String> filtered;
        if (filter.isEmpty()) {
            filtered = currentKeys;
        } else {
            filtered = currentKeys.stream()
                    .filter(k -> k.toLowerCase().contains(filter))
                    .toList();
        }

        var items = buildGroupedItems(filtered);
        for (var item : items) {
            listModel.addElement(item);
        }

        // Select first Entry
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i) instanceof GroupedItem.Entry) {
                propertyList.setSelectedIndex(i);
                break;
            }
        }
    }

    private void onPropertySelected() {
        var sel = propertyList.getSelectedValue();
        if (!(sel instanceof GroupedItem.Entry entry)) {
            selectedKey = null;
            detailTitle.setText("Select a property");
            detailPanel.removeAll();
            detailPanel.revalidate();
            detailPanel.repaint();
            return;
        }

        selectedKey = entry.fullKey();
        var section = (String) sectionCombo.getSelectedItem();
        detailTitle.setText(section + "." + selectedKey);

        detailPanel.removeAll();

        for (var env : environments) {
            var displayEnv = Environment.DEFAULT_ENV.equals(env) ? Environment.DEFAULT_DISPLAY : env;

            var prop = allProperties.stream()
                    .filter(p -> p.section().equals(section) && p.key().equals(selectedKey) && p.env().equals(env))
                    .findFirst();
            var value = prop.map(p -> p.isNullValue() ? "null" : p.value()).orElse("");

            var comment = prop.map(Property::comment).orElse("");

            // Value row
            var row = new JPanel(new BorderLayout(8, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

            var label = new JLabel(displayEnv);
            label.setPreferredSize(new Dimension(130, 24));
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            if (Environment.DEFAULT_ENV.equals(env)) {
                label.setForeground(JBColor.namedColor("SCT.defaultEnv", new Color(100, 160, 255)));
            }
            row.add(label, BorderLayout.WEST);

            final var envName = env;

            // Value + Comment in one row using split panel
            var field = new JTextField(value);
            field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            if (value.isEmpty()) {
                field.putClientProperty("JTextField.placeholderText", "(inherit)");
            }
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    onFieldChanged(section, selectedKey, envName, field.getText(), null);
                }
            });

            var commentField = new JTextField(comment != null ? comment : "");
            commentField.setFont(commentField.getFont().deriveFont(Font.ITALIC, 11f));
            commentField.setForeground(JBColor.GRAY);
            commentField.putClientProperty("JTextField.placeholderText", "# comment");
            commentField.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    onCommentChanged(section, selectedKey, envName, commentField.getText());
                }
            });

            var fieldsPanel = new JPanel(new GridLayout(1, 2, 4, 0));
            fieldsPanel.add(field);
            fieldsPanel.add(commentField);
            row.add(fieldsPanel, BorderLayout.CENTER);

            detailPanel.add(row);
            detailPanel.add(Box.createVerticalStrut(4));
        }

        detailPanel.add(Box.createVerticalGlue());
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void onFieldChanged(String section, String key, String env, String value, String comment) {
        var existingComment = allProperties.stream()
                .filter(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env))
                .findFirst().map(Property::comment).orElse(null);
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));
        if (value != null && !value.isEmpty()) {
            var c = comment != null ? comment : existingComment;

            // Try Spring metadata type first, fallback to auto-detect
            var fullKey = section + "." + key;
            var meta = SpringMetadataService.getInstance(project).getProperty(fullKey);
            if (meta != null) {
                var vt = meta.toValueType();
                allProperties.add(new Property(section, key, env, "null".equals(value) ? Property.NULL_VALUE : value, vt, c));
            } else {
                allProperties.add(Property.ofParsed(section, key, env, value, c));
            }
        }
        setStatus("Unsaved changes", true);
    }

    private void onCommentChanged(String section, String key, String env, String comment) {
        var prop = allProperties.stream()
                .filter(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env))
                .findFirst().orElse(null);
        if (prop != null) {
            allProperties.remove(prop);
            allProperties.add(prop.withComment(comment.isBlank() ? null : comment));
            setStatus("Unsaved changes", true);
        }
    }

    // === Actions ===

    private void addProperty() {
        var section = (String) sectionCombo.getSelectedItem();
        if (section == null) return;
        var key = JOptionPane.showInputDialog(mainPanel, "New property key:");
        if (key == null || key.isBlank()) return;
        for (var env : environments) allProperties.add(Property.of(section, key.trim(), env, ""));
        onSectionChanged();
        // Select the new property
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i) instanceof GroupedItem.Entry e && e.fullKey().equals(key.trim())) {
                propertyList.setSelectedIndex(i);
                break;
            }
        }
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
                    ? new Property(p.section(), newKey.trim(), p.env(), p.value(), p.valueType(), p.comment()) : p);
        }
        allProperties = updated;
        onSectionChanged();
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
        for (var p : allProperties)
            updated.add(p.section().equals(old) ? new Property(name.trim(), p.key(), p.env(), p.value(), p.valueType(), p.comment()) : p);
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
        onPropertySelected();
        setStatus("Env added (unsaved)", true);
    }

    private void removeEnv() {
        var envs = environments.toArray(String[]::new);
        var env = (String) JOptionPane.showInputDialog(mainPanel, "Select environment to delete:",
                "Delete Env", JOptionPane.QUESTION_MESSAGE, null, envs, null);
        if (env == null) return;
        environments.remove(env);
        allProperties.removeIf(p -> p.env().equals(env));
        onPropertySelected();
        setStatus("Env deleted (unsaved)", true);
    }

    private void save() {
        try {
            var envOrder = resolveEnvOrder();
            new MasterMarkdownWriter()
                    .withEnvOrder(envOrder.lifecycle, envOrder.region)
                    .write(allProperties, Path.of(file.getPath()));
            ApplicationManager.getApplication().invokeLater(() ->
                    WriteAction.run(() -> file.refresh(false, false)));
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

    // === Grouped list renderer ===

    private class GroupedListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            if (value instanceof GroupedItem.Header header) {
                var panel = new JPanel(new BorderLayout());
                panel.setOpaque(true);
                panel.setBackground(list.getBackground());

                // Separator line
                if (index > 0) {
                    panel.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0,
                                    JBColor.namedColor("SCT.separator", new Color(80, 80, 80))),
                            BorderFactory.createEmptyBorder(4, 6, 2, 6)));
                } else {
                    panel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                }

                var label = new JLabel(header.groupName() + "  (" + header.count() + ")");
                label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
                label.setForeground(JBColor.namedColor("SCT.groupHeader", new Color(140, 140, 140)));
                panel.add(label, BorderLayout.CENTER);
                return panel;
            }

            if (value instanceof GroupedItem.Entry entry) {
                var c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                var section = (String) sectionCombo.getSelectedItem();

                // Override indicator
                var hasOverrides = section != null && allProperties.stream()
                        .anyMatch(p -> p.section().equals(section) && p.key().equals(entry.fullKey())
                                && !p.env().equals(Environment.DEFAULT_ENV)
                                && p.value() != null && !p.value().isEmpty());

                var prefix = hasOverrides ? "● " : "   ";
                setText(prefix + entry.displayKey());
                setFont(getFont().deriveFont(12f));
                setBorder(BorderFactory.createEmptyBorder(1, 12, 1, 4));
                return c;
            }

            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
    }

    // === Config resolution: .sct-config.yml > IDE settings ===

    private record EnvOrder(List<String> lifecycle, List<String> region) {}

    private EnvOrder resolveEnvOrder() {
        var basePath = project.getBasePath();
        if (basePath != null) {
            var projectConfig = com.pinkmandarin.sct.core.config.SctProjectConfig.load(java.nio.file.Path.of(basePath));
            if (projectConfig != null) {
                return new EnvOrder(projectConfig.lifecycleOrder(), projectConfig.regionOrder());
            }
        }
        var sctS = com.pinkmandarin.sct.intellij.SctSettings.getInstance(project);
        return new EnvOrder(sctS.getLifecycleOrderList(), sctS.getRegionOrderList());
    }

    // === FileEditor ===

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
