package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.model.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY;

/**
 * Shows a table view of selected YAML files with property/value/profile filtering.
 * Integrated from YamlLens project, reimplemented with sct-core.
 */
public class YamlLensAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var files = e.getData(VIRTUAL_FILE_ARRAY);
        if (files == null || files.length == 0) return;

        var yamlFiles = Arrays.stream(files)
                .filter(f -> f.getName().endsWith(".yml") || f.getName().endsWith(".yaml"))
                .toArray(VirtualFile[]::new);
        if (yamlFiles.length == 0) return;

        var rows = buildRows(yamlFiles);
        var profiles = rows.stream().map(r -> r.profile).distinct().sorted(
                (a, b) -> "default".equals(a) ? -1 : "default".equals(b) ? 1 : a.compareTo(b)
        ).toList();

        new YamlLensDialog(e.getProject(), rows, profiles).show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var files = e.getData(VIRTUAL_FILE_ARRAY);
        boolean hasYaml = files != null && Arrays.stream(files)
                .anyMatch(f -> f.getName().endsWith(".yml") || f.getName().endsWith(".yaml"));
        e.getPresentation().setEnabledAndVisible(hasYaml);
    }

    private List<PropertyRow> buildRows(VirtualFile[] files) {
        var rows = new ArrayList<PropertyRow>();
        var importer = new YamlImporter();

        for (var file : files) {
            try {
                var envName = YamlImporter.extractEnvName(Path.of(file.getName()));
                var props = importer.parseFile(Path.of(file.getPath()), envName);
                for (var prop : props) {
                    var fullKey = prop.section() + "." + prop.key();
                    var value = prop.isNullValue() ? "null" : (prop.value() != null ? prop.value() : "");
                    rows.add(new PropertyRow(fullKey, prop.env(), value));
                }
            } catch (IOException ignored) {}
        }

        rows.sort(Comparator.comparing((PropertyRow r) -> r.property).thenComparing(r -> r.profile));
        return rows;
    }

    record PropertyRow(String property, String profile, String value) {}

    private static class YamlLensDialog extends DialogWrapper {

        private final List<PropertyRow> allRows;
        private final List<String> profiles;
        private JBTable table;
        private LensTableModel tableModel;

        YamlLensDialog(@Nullable com.intellij.openapi.project.Project project,
                       List<PropertyRow> rows, List<String> profiles) {
            super(project, true);
            this.allRows = rows;
            this.profiles = profiles;
            setTitle(SctBundle.message("yamllens.title"));
            setSize(1100, 700);
            init();
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
            table.setRowSorter(sorter);

            var propertyFilter = new SearchTextField(false);
            propertyFilter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                    applyFilter(sorter, propertyFilter.getText(),
                            null, null);
                }
            });

            var valueFilter = new SearchTextField(false);
            valueFilter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                    applyFilter(sorter, propertyFilter.getText(),
                            valueFilter.getText(), null);
                }
            });

            var profileCombo = new JComboBox<>(buildProfileOptions());
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
                writer.write('\ufeff'); // BOM
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
            } catch (IOException ignored) {}
        }

        private String csvEscape(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    private static class LensTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Property", "Profile", "Value"};
        final List<PropertyRow> rows;

        LensTableModel(List<PropertyRow> rows) {
            this.rows = rows;
        }

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
