package com.pinkmandarin.sct.intellij.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WYSIWYG editor for SCT master Markdown files using JCEF (embedded Chromium).
 * Renders markdown as HTML with contenteditable table cells.
 * Edits in the browser sync back to the .md file.
 */
public class SctTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctTableEditor.class);

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;
    private JBCefBrowser browser;
    private JBCefJSQuery saveQuery;

    private List<Property> allProperties = new ArrayList<>();
    private List<String> sections = new ArrayList<>();
    private List<String> environments = new ArrayList<>();

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
        browser = new JBCefBrowser();
        saveQuery = JBCefJSQuery.create(browser);

        // Handle cell edits from JavaScript
        saveQuery.addHandler((data) -> {
            handleCellEdit(data);
            return new JBCefJSQuery.Response("ok");
        });

        // Load HTML after browser is ready
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                // Inject the save callback function
                b.executeJavaScript(
                        "window.__sctSave = function(data) { " + saveQuery.inject("data") + " };",
                        "", 0);
            }
        }, browser.getCefBrowser());

        var html = buildHtml();
        browser.loadHTML(html);

        // Toolbar with Save + Reload buttons
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        var saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveToFile());
        toolbar.add(saveBtn);

        var reloadBtn = new JButton("Reload");
        reloadBtn.addActionListener(e -> {
            loadFromFile();
            browser.loadHTML(buildHtml());
        });
        toolbar.add(reloadBtn);

        mainPanel.add(toolbar, BorderLayout.NORTH);
        mainPanel.add(browser.getComponent(), BorderLayout.CENTER);
    }

    private String buildHtml() {
        var sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<style>");
        sb.append(CSS);
        sb.append("</style></head><body>");

        sb.append("<h1>").append(escHtml(file.getName())).append("</h1>");
        sb.append("<p class='hint'>Click any cell to edit. Changes are tracked automatically.</p>");

        for (var section : sections) {
            var sectionProps = allProperties.stream()
                    .filter(p -> p.section().equals(section))
                    .toList();
            var keys = sectionProps.stream()
                    .map(Property::key).distinct().sorted().toList();

            sb.append("<h2>").append(escHtml(section)).append("</h2>");
            sb.append("<table><thead><tr><th class='env-col'>env</th>");
            for (var key : keys) {
                sb.append("<th>").append(escHtml(key)).append("</th>");
            }
            sb.append("</tr></thead><tbody>");

            for (var env : environments) {
                var displayEnv = Environment.DEFAULT_ENV.equals(env)
                        ? Environment.DEFAULT_DISPLAY : env;
                sb.append("<tr><td class='env-col'>").append(escHtml(displayEnv)).append("</td>");
                for (var key : keys) {
                    var prop = sectionProps.stream()
                            .filter(p -> p.key().equals(key) && p.env().equals(env))
                            .findFirst();
                    var value = prop.map(p -> p.isNullValue() ? "null" : p.value()).orElse("");
                    var cssClass = value.isEmpty() ? "empty" : "null".equals(value) ? "null-val" : "";

                    sb.append("<td class='").append(cssClass).append("' contenteditable='true' ")
                      .append("data-section='").append(escAttr(section)).append("' ")
                      .append("data-key='").append(escAttr(key)).append("' ")
                      .append("data-env='").append(escAttr(env)).append("' ")
                      .append("onblur='onCellEdit(this)'>");
                    sb.append(value.isEmpty() ? "" : escHtml(value));
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("<script>");
        sb.append(JS);
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void handleCellEdit(String data) {
        // Format: section|key|env|value
        var parts = data.split("\\|", 4);
        if (parts.length < 4) return;

        var section = parts[0];
        var key = parts[1];
        var env = parts[2];
        var value = parts[3];

        // Remove old property
        allProperties.removeIf(p -> p.section().equals(section)
                && p.key().equals(key) && p.env().equals(env));

        // Add updated property
        if (!value.isEmpty()) {
            if ("null".equals(value)) {
                allProperties.add(Property.of(section, key, env, null));
            } else {
                allProperties.add(Property.of(section, key, env, value));
            }
        }
    }

    private void saveToFile() {
        try {
            new MasterMarkdownWriter().write(allProperties, Path.of(file.getPath()));
            file.refresh(false, false);
            browser.getCefBrowser().executeJavaScript(
                    "document.getElementById('status').textContent = 'Saved!'; " +
                    "document.getElementById('status').className = 'status saved';",
                    "", 0);
        } catch (IOException e) {
            LOG.warn("Failed to save: " + file.getPath(), e);
            browser.getCefBrowser().executeJavaScript(
                    "document.getElementById('status').textContent = 'Save failed: " +
                    escJs(e.getMessage()) + "'; document.getElementById('status').className = 'status error';",
                    "", 0);
        }
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        return escHtml(s).replace("'", "&#39;");
    }

    private static String escJs(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    // --- FileEditor interface ---

    @Override public @NotNull JComponent getComponent() { return mainPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return browser.getComponent(); }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public @Nullable VirtualFile getFile() { return file; }

    @Override
    public void dispose() {
        if (saveQuery != null) saveQuery.dispose();
        if (browser != null) browser.dispose();
    }

    // --- CSS ---

    private static final String CSS = """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            padding: 16px 24px;
            background: #1e1e1e; color: #ccc;
            line-height: 1.5;
        }
        @media (prefers-color-scheme: light) {
            body { background: #fff; color: #333; }
            table { border-color: #ddd; }
            th { background: #f5f5f5; color: #333; }
            td { border-color: #e0e0e0; }
            td:focus { background: #e8f0fe; outline-color: #4285f4; }
            td.empty::before { color: #bbb; }
            td.null-val { color: #d32f2f; }
            h1, h2 { color: #222; }
            .hint { color: #888; }
            .status.saved { color: #2e7d32; }
            .status.error { color: #d32f2f; }
        }
        h1 { font-size: 20px; margin-bottom: 4px; color: #e0e0e0; }
        h2 { font-size: 15px; margin: 20px 0 8px; color: #8ab4f8;
             border-bottom: 1px solid #333; padding-bottom: 4px; }
        .hint { font-size: 12px; color: #777; margin-bottom: 12px; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 16px; font-size: 13px; }
        th, td { border: 1px solid #3a3a3a; padding: 5px 8px; text-align: left; min-width: 80px; }
        th { background: #2a2a2a; color: #aaa; font-weight: 600; font-size: 12px;
             position: sticky; top: 0; z-index: 1; }
        td { transition: background 0.15s; }
        td:focus { background: #1a3a5c; outline: 2px solid #4285f4; outline-offset: -2px; }
        td.empty::before { content: '(inherit)'; color: #555; font-style: italic; }
        td.null-val { color: #ef5350; font-style: italic; }
        .env-col { font-weight: 600; background: #252525; min-width: 100px; }
        #status { padding: 8px 0; font-size: 12px; }
        .status.saved { color: #66bb6a; }
        .status.error { color: #ef5350; }
    """;

    // --- JavaScript ---

    private static final String JS = """
        function onCellEdit(cell) {
            var section = cell.getAttribute('data-section');
            var key = cell.getAttribute('data-key');
            var env = cell.getAttribute('data-env');
            var value = cell.textContent.trim();

            // Update visual class
            cell.className = value === '' ? 'empty' : (value === 'null' ? 'null-val' : '');

            // Send to Java
            if (window.__sctSave) {
                window.__sctSave(section + '|' + key + '|' + env + '|' + value);
            }

            // Show unsaved indicator
            var status = document.getElementById('status');
            if (status) { status.textContent = 'Unsaved changes'; status.className = 'status'; }
        }

        // Prevent Enter from creating new lines in cells
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && e.target.hasAttribute('contenteditable')) {
                e.preventDefault();
                e.target.blur();
            }
            // Tab to next cell
            if (e.key === 'Tab' && e.target.hasAttribute('contenteditable')) {
                e.preventDefault();
                var cells = Array.from(document.querySelectorAll('td[contenteditable]'));
                var idx = cells.indexOf(e.target);
                var next = e.shiftKey ? idx - 1 : idx + 1;
                if (next >= 0 && next < cells.length) {
                    e.target.blur();
                    cells[next].focus();
                }
            }
        });
    """;
}
