package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Shows a modeless table view of selected YAML/Markdown files.
 * Editor remains fully interactive while the dialog is open.
 * Double-click a row to navigate to the property in the source file.
 */
public class YamlLensAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;

        var yamlFiles = YamlFileCollector.collect(e);
        if (yamlFiles.isEmpty()) return;

        var rows = buildRows(yamlFiles.toArray(VirtualFile[]::new));
        var profiles = rows.stream().map(r -> r.profile).distinct().sorted(
                (a, b) -> "default".equals(a) ? -1 : "default".equals(b) ? 1 : a.compareTo(b)
        ).toList();

        var dialog = new YamlLensDialog(project, rows, profiles);
        dialog.show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(true);
    }

    private List<PropertyRow> buildRows(VirtualFile[] files) {
        var rows = new ArrayList<PropertyRow>();
        var importer = new YamlImporter();
        var mdParser = new MasterMarkdownParser();

        for (var file : files) {
            try {
                if (YamlFileCollector.isMarkdown(file)) {
                    var result = mdParser.parse(Path.of(file.getPath()));
                    for (var prop : result.properties()) {
                        var fullKey = prop.section() + "." + prop.key();
                        var value = prop.isNullValue() ? "null" : (prop.value() != null ? prop.value() : "");
                        rows.add(new PropertyRow(fullKey, prop.env(), value, file.getPath(), -1));
                    }
                } else {
                    var envName = YamlImporter.extractEnvName(Path.of(file.getName()));
                    var lineMap = buildLineMap(Path.of(file.getPath()));
                    var props = importer.parseFile(Path.of(file.getPath()), envName);
                    for (var prop : props) {
                        var fullKey = prop.section() + "." + prop.key();
                        var value = prop.isNullValue() ? "null" : (prop.value() != null ? prop.value() : "");
                        var line = lineMap.getOrDefault(fullKey, -1);
                        rows.add(new PropertyRow(fullKey, prop.env(), value, file.getPath(), line));
                    }
                }
            } catch (IOException ex) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(YamlLensAction.class)
                            .warn("Failed to parse: " + file.getPath(), ex);
                }
        }

        rows.sort(Comparator.comparing((PropertyRow r) -> r.property, NaturalOrderComparator.INSTANCE)
                .thenComparing(r -> r.profile));
        return rows;
    }

    /**
     * Builds a map of full property key -> source line number using SnakeYAML Node API.
     * Each Node carries a startMark with the exact line number from the YAML source.
     */
    private Map<String, Integer> buildLineMap(Path file) {
        var lineMap = new HashMap<String, Integer>();
        try {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            var loaderOptions = new LoaderOptions();
            var composer = new Composer(
                    new ParserImpl(new StreamReader(new StringReader(content)), loaderOptions),
                    new Resolver(),
                    loaderOptions
            );
            while (composer.checkNode()) {
                var rootNode = composer.getNode();
                if (rootNode instanceof MappingNode mappingNode) {
                    for (var tuple : mappingNode.getValue()) {
                        if (tuple.getKeyNode() instanceof ScalarNode keyNode) {
                            var section = keyNode.getValue();
                            if (tuple.getValueNode() instanceof MappingNode valueMapping) {
                                collectLineNumbers(section, "", valueMapping, lineMap);
                            } else {
                                lineMap.put(section + ".__scalar__",
                                        tuple.getValueNode().getStartMark().getLine());
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            com.intellij.openapi.diagnostic.Logger.getInstance(YamlLensAction.class)
                    .warn("Failed to build line map: " + file, ex);
        }
        return lineMap;
    }

    private void collectLineNumbers(String section, String prefix, MappingNode node,
                                    Map<String, Integer> lineMap) {
        for (var tuple : node.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) continue;
            var escapedKey = keyNode.getValue().replace("\\", "\\\\").replace(".", "\\.");
            var key = prefix.isEmpty() ? escapedKey : prefix + "." + escapedKey;
            var valueNode = tuple.getValueNode();

            if (valueNode instanceof MappingNode mappingValue) {
                collectLineNumbers(section, key, mappingValue, lineMap);
            } else if (valueNode instanceof SequenceNode sequenceNode) {
                var items = sequenceNode.getValue();
                for (int i = 0; i < items.size(); i++) {
                    var item = items.get(i);
                    if (item instanceof MappingNode itemMapping) {
                        collectLineNumbers(section, key + "[" + i + "]", itemMapping, lineMap);
                    } else {
                        lineMap.put(section + "." + key + "[" + i + "]",
                                item.getStartMark().getLine());
                    }
                }
            } else {
                lineMap.put(section + "." + key, keyNode.getStartMark().getLine());
            }
        }
    }

    record PropertyRow(String property, String profile, String value, String filePath, int sourceLine) {}

    private static class YamlLensDialog extends DialogWrapper {

        private final Project project;
        private final List<PropertyRow> allRows;
        private final List<String> profiles;
        private JBTable table;
        private LensTableModel tableModel;

        YamlLensDialog(@NotNull Project project, List<PropertyRow> rows, List<String> profiles) {
            super(project, false);
            this.project = project;
            this.allRows = rows;
            this.profiles = profiles;
            setTitle(SctBundle.message("yamllens.title"));
            setModal(false);
            init();
            getWindow().setMinimumSize(new Dimension(900, 500));
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            tableModel = new LensTableModel(allRows);
            table = new JBTable(tableModel);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.getColumnModel().getColumn(0).setPreferredWidth(350);
            table.getColumnModel().getColumn(1).setPreferredWidth(120);
            table.getColumnModel().getColumn(2).setPreferredWidth(500);

            var sorter = new TableRowSorter<>(tableModel);
            sorter.setComparator(0, NaturalOrderComparator.INSTANCE);
            table.setRowSorter(sorter);

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        var viewRow = table.getSelectedRow();
                        if (viewRow < 0) return;
                        var modelRow = table.convertRowIndexToModel(viewRow);
                        navigateToProperty(project, tableModel.rows.get(modelRow));
                    }
                }
            });

            var propertyFilter = new SearchTextField(false);
            var valueFilter = new SearchTextField(false);
            var profileCombo = new JComboBox<>(buildProfileOptions());

            propertyFilter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                    applyFilter(sorter, propertyFilter.getText(), valueFilter.getText(),
                            (String) profileCombo.getSelectedItem());
                }
            });
            valueFilter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                    applyFilter(sorter, propertyFilter.getText(), valueFilter.getText(),
                            (String) profileCombo.getSelectedItem());
                }
            });
            profileCombo.addActionListener(e -> applyFilter(sorter,
                    propertyFilter.getText(), valueFilter.getText(),
                    (String) profileCombo.getSelectedItem()));

            var exportCsvBtn = new JButton(SctBundle.message("yamllens.exportCsv"));
            exportCsvBtn.addActionListener(e -> exportCsv());

            var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
            toolbar.add(exportCsvBtn);
            toolbar.add(new JLabel(SctBundle.message("yamllens.propertyFilter")));
            toolbar.add(propertyFilter);
            toolbar.add(new JLabel(SctBundle.message("yamllens.valueFilter")));
            toolbar.add(valueFilter);
            toolbar.add(new JLabel(SctBundle.message("yamllens.profile")));
            toolbar.add(profileCombo);

            var panel = new JPanel(new BorderLayout());
            panel.add(toolbar, BorderLayout.NORTH);
            panel.add(new JBScrollPane(table), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(1000, 600));
            return panel;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }

        private String[] buildProfileOptions() {
            var options = new ArrayList<String>();
            options.add("");
            options.addAll(profiles);
            return options.toArray(String[]::new);
        }

        private void applyFilter(TableRowSorter<LensTableModel> sorter,
                                 String propText, String valText, String profile) {
            var filters = new ArrayList<RowFilter<LensTableModel, Integer>>();
            if (propText != null && !propText.isBlank()) {
                filters.add(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(propText), 0));
            }
            if (valText != null && !valText.isBlank()) {
                filters.add(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(valText), 2));
            }
            if (profile != null && !profile.isBlank()) {
                filters.add(RowFilter.regexFilter("^" + java.util.regex.Pattern.quote(profile) + "$", 1));
            }
            sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
        }

        private void exportCsv() {
            var chooser = new JFileChooser();
            chooser.setDialogTitle(SctBundle.message("yamllens.exportCsv"));
            if (chooser.showSaveDialog(getContentPanel()) != JFileChooser.APPROVE_OPTION) return;

            try (var writer = new FileWriter(chooser.getSelectedFile(), StandardCharsets.UTF_8)) {
                writer.write('\ufeff');
                writer.write("Property,Profile,Value\n");
                for (int i = 0; i < table.getRowCount(); i++) {
                    var row = table.convertRowIndexToModel(i);
                    writer.write(csvEscape(tableModel.rows.get(row).property));
                    writer.write(',');
                    writer.write(csvEscape(tableModel.rows.get(row).profile));
                    writer.write(',');
                    writer.write(csvEscape(tableModel.rows.get(row).value));
                    writer.write('\n');
                }
            } catch (IOException ex) {
                com.intellij.openapi.diagnostic.Logger.getInstance(YamlLensAction.class)
                        .warn("CSV export failed", ex);
            }
        }

        private String csvEscape(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    private static void navigateToProperty(Project project, PropertyRow row) {
        var vf = LocalFileSystem.getInstance().findFileByPath(row.filePath);
        if (vf == null) return;

        int line = Math.max(row.sourceLine, 0);
        var descriptor = new OpenFileDescriptor(project, vf, line, 0);
        var editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        if (editor != null) {
            editor.getCaretModel().moveToOffset(editor.getDocument().getLineStartOffset(line));
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        }
    }

    static class LensTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Property", "Profile", "Value"};
        final List<PropertyRow> rows;

        LensTableModel(List<PropertyRow> rows) { this.rows = rows; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            var r = rows.get(row);
            return switch (col) {
                case 0 -> r.property;
                case 1 -> r.profile;
                case 2 -> r.value;
                default -> "";
            };
        }
    }
}
