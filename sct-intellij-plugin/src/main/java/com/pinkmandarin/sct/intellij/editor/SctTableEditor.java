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

public class SctTableEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(SctTableEditor.class);

    private final Project project;
    private final VirtualFile file;
    private final JPanel mainPanel;
    private JBCefBrowser browser;
    private JBCefJSQuery actionQuery;

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
        actionQuery = JBCefJSQuery.create(browser);

        actionQuery.addHandler((data) -> {
            handleAction(data);
            return new JBCefJSQuery.Response("ok");
        });

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                b.executeJavaScript(
                        "window.__sct = function(data) { " + actionQuery.inject("data") + " };",
                        "", 0);
            }
        }, browser.getCefBrowser());

        browser.loadHTML(buildHtml());
        mainPanel.add(browser.getComponent(), BorderLayout.CENTER);
    }

    private void reload() {
        loadFromFile();
        browser.loadHTML(buildHtml());
    }

    private String buildHtml() {
        var sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>").append(CSS).append("</style></head><body>");

        // Top toolbar
        sb.append("<div class='toolbar'>");
        sb.append("<button onclick=\"act('save')\">💾 Save</button>");
        sb.append("<button onclick=\"act('reload')\">🔄 Reload</button>");
        sb.append("<button onclick=\"act('add-section')\">+ Section</button>");
        sb.append("<span id='status'></span>");
        sb.append("</div>");

        for (var section : sections) {
            var sectionProps = allProperties.stream()
                    .filter(p -> p.section().equals(section)).toList();
            var keys = sectionProps.stream()
                    .map(Property::key).distinct().sorted().toList();

            // Section heading — editable
            sb.append("<div class='section-header'>");
            sb.append("<h2 contenteditable='true' spellcheck='false' data-orig='").append(escAttr(section))
              .append("' onblur='onSectionRename(this)'>").append(escHtml(section)).append("</h2>");
            sb.append("</div>");

            // Table
            sb.append("<div class='table-wrap'><table data-section='").append(escAttr(section)).append("'>");

            // Header row — editable keys + add/remove
            sb.append("<thead><tr><th class='env-hdr'>env</th>");
            for (var key : keys) {
                sb.append("<th contenteditable='true' spellcheck='false' data-section='").append(escAttr(section))
                  .append("' data-orig-key='").append(escAttr(key))
                  .append("' onblur='onKeyRename(this)'>").append(escHtml(key))
                  .append("<span class='del-col' onclick='onDeleteCol(this)' title='Delete column'>×</span>")
                  .append("</th>");
            }
            sb.append("<th class='add-col' onclick=\"onAddCol('").append(escAttr(section)).append("')\" title='Add column'>+</th>");
            sb.append("</tr></thead>");

            // Data rows
            sb.append("<tbody>");
            for (var env : environments) {
                var displayEnv = Environment.DEFAULT_ENV.equals(env) ? Environment.DEFAULT_DISPLAY : env;
                sb.append("<tr><td class='env-cell'>").append(escHtml(displayEnv))
                  .append("<span class='del-row' onclick=\"onDeleteRow('").append(escAttr(env)).append("')\" title='Delete environment'>×</span>")
                  .append("</td>");
                for (var key : keys) {
                    var prop = sectionProps.stream()
                            .filter(p -> p.key().equals(key) && p.env().equals(env)).findFirst();
                    var value = prop.map(p -> p.isNullValue() ? "null" : p.value()).orElse("");
                    var cls = value.isEmpty() ? "empty" : "null".equals(value) ? "null-val" : "";

                    sb.append("<td class='").append(cls).append("' contenteditable='true' spellcheck='false' ")
                      .append("data-section='").append(escAttr(section))
                      .append("' data-key='").append(escAttr(key))
                      .append("' data-env='").append(escAttr(env))
                      .append("' onblur='onCellEdit(this)' onfocus='onCellFocus(this)'>")
                      .append(value.isEmpty() ? "" : escHtml(value))
                      .append("</td>");
                }
                sb.append("<td class='add-placeholder'></td></tr>");
            }

            // Add environment row
            sb.append("<tr class='add-row'><td colspan='").append(keys.size() + 2)
              .append("' onclick='onAddEnv()'>+ Add environment</td></tr>");

            sb.append("</tbody></table></div>");
        }

        // Modal dialog (replaces prompt/confirm which don't work in JCEF)
        sb.append("""
            <div class='modal-overlay' id='modal'>
                <div class='modal'>
                    <h3 id='modal-title'></h3>
                    <input id='modal-input' type='text' />
                    <div class='modal-btns'>
                        <button class='btn-cancel' onclick='modalCancel()'>Cancel</button>
                        <button class='btn-ok' onclick='modalOk()'>OK</button>
                    </div>
                </div>
            </div>
            <div class='modal-overlay' id='confirm-modal'>
                <div class='modal'>
                    <h3 id='confirm-title'></h3>
                    <div class='modal-btns'>
                        <button class='btn-cancel' onclick='confirmCancel()'>Cancel</button>
                        <button class='btn-ok' onclick='confirmOk()'>OK</button>
                    </div>
                </div>
            </div>
        """);

        sb.append("<script>").append(JS).append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // --- Action handler from JavaScript ---

    private void handleAction(String data) {
        if (data.startsWith("save")) {
            SwingUtilities.invokeLater(this::saveToFile);
        } else if (data.startsWith("reload")) {
            SwingUtilities.invokeLater(this::reload);
        } else if (data.startsWith("cell|")) {
            handleCellEdit(data.substring(5));
        } else if (data.startsWith("rename-section|")) {
            handleSectionRename(data.substring(15));
        } else if (data.startsWith("rename-key|")) {
            handleKeyRename(data.substring(11));
        } else if (data.startsWith("add-col|")) {
            handleAddCol(data.substring(8));
        } else if (data.startsWith("del-col|")) {
            handleDeleteCol(data.substring(8));
        } else if (data.startsWith("add-env|")) {
            handleAddEnv(data.substring(8));
        } else if (data.startsWith("del-env|")) {
            handleDeleteEnv(data.substring(8));
        } else if (data.startsWith("add-section|")) {
            handleAddSection(data.substring(12));
        }
    }

    private void handleCellEdit(String data) {
        var parts = data.split("\\|", 4);
        if (parts.length < 4) return;
        var section = parts[0]; var key = parts[1]; var env = parts[2]; var value = parts[3];

        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key) && p.env().equals(env));
        if (!value.isEmpty()) {
            allProperties.add("null".equals(value) ? Property.of(section, key, env, null) : Property.of(section, key, env, value));
        }
    }

    private void handleSectionRename(String data) {
        var parts = data.split("\\|", 2);
        if (parts.length < 2) return;
        var oldName = parts[0]; var newName = parts[1].trim();
        if (newName.isEmpty() || oldName.equals(newName)) return;

        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            if (p.section().equals(oldName)) {
                updated.add(new Property(newName, p.key(), p.env(), p.value(), p.valueType()));
            } else {
                updated.add(p);
            }
        }
        allProperties = updated;
        sections.replaceAll(s -> s.equals(oldName) ? newName : s);
    }

    private void handleKeyRename(String data) {
        var parts = data.split("\\|", 3);
        if (parts.length < 3) return;
        var section = parts[0]; var oldKey = parts[1]; var newKey = parts[2].trim();
        if (newKey.isEmpty() || oldKey.equals(newKey)) return;

        var updated = new ArrayList<Property>();
        for (var p : allProperties) {
            if (p.section().equals(section) && p.key().equals(oldKey)) {
                updated.add(new Property(p.section(), newKey, p.env(), p.value(), p.valueType()));
            } else {
                updated.add(p);
            }
        }
        allProperties = updated;
    }

    private void handleAddCol(String data) {
        var parts = data.split("\\|", 2);
        if (parts.length < 2) return;
        var section = parts[0]; var key = parts[1].trim();
        if (key.isEmpty()) return;
        for (var env : environments) {
            allProperties.add(Property.of(section, key, env, ""));
        }
    }

    private void handleDeleteCol(String data) {
        var parts = data.split("\\|", 2);
        if (parts.length < 2) return;
        var section = parts[0]; var key = parts[1];
        allProperties.removeIf(p -> p.section().equals(section) && p.key().equals(key));
    }

    private void handleAddEnv(String envName) {
        if (envName.isBlank() || environments.contains(envName.trim())) return;
        environments.add(envName.trim());
    }

    private void handleDeleteEnv(String env) {
        environments.remove(env);
        allProperties.removeIf(p -> p.env().equals(env));
    }

    private void handleAddSection(String name) {
        if (name.isBlank() || sections.contains(name.trim())) return;
        sections.add(name.trim());
        for (var env : environments) {
            allProperties.add(Property.of(name.trim(), "key", env, ""));
        }
        // Section add requires full reload since it's a new table
        SwingUtilities.invokeLater(this::reload);
    }

    private void saveToFile() {
        try {
            new MasterMarkdownWriter().write(allProperties, Path.of(file.getPath()));
            file.refresh(false, false);
            execJs("showStatus('Saved!', 'saved')");
        } catch (IOException e) {
            LOG.warn("Failed to save", e);
            execJs("showStatus('Save failed: " + escJs(e.getMessage()) + "', 'error')");
        }
    }

    private void execJs(String js) {
        browser.getCefBrowser().executeJavaScript(js, "", 0);
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

    // --- FileEditor ---

    @Override public @NotNull JComponent getComponent() { return mainPanel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return browser.getComponent(); }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener l) {}
    @Override public @Nullable VirtualFile getFile() { return file; }
    @Override public void dispose() {
        if (actionQuery != null) actionQuery.dispose();
        if (browser != null) browser.dispose();
    }

    // ========== CSS ==========

    private static final String CSS = """
        :root {
            --bg: #1e1f22; --fg: #bcbec4; --border: #393b40;
            --hdr-bg: #2b2d30; --hdr-fg: #dfe1e5;
            --focus-bg: #2d4f67; --focus-border: #4285f4;
            --empty-fg: #5c5e63; --null-fg: #e06c75;
            --section-fg: #6aafff; --btn-bg: #3574a5; --btn-fg: #fff;
            --add-bg: #2a3a2a; --add-fg: #6aab73;
            --del-fg: #e06c75; --saved-fg: #6aab73; --error-fg: #e06c75;
            --toolbar-bg: #2b2d30;
        }
        @media (prefers-color-scheme: light) {
            :root {
                --bg: #fff; --fg: #1f1f1f; --border: #d4d4d4;
                --hdr-bg: #f3f3f3; --hdr-fg: #1f1f1f;
                --focus-bg: #e3f2fd; --focus-border: #1976d2;
                --empty-fg: #aaa; --null-fg: #c62828;
                --section-fg: #1565c0; --btn-bg: #1976d2; --btn-fg: #fff;
                --add-bg: #e8f5e9; --add-fg: #2e7d32;
                --del-fg: #c62828; --saved-fg: #2e7d32; --error-fg: #c62828;
                --toolbar-bg: #f5f5f5;
            }
        }
        * { box-sizing: border-box; }
        body { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 13px;
               background: var(--bg); color: var(--fg); padding: 0; margin: 0; }

        .toolbar { background: var(--toolbar-bg);
                   padding: 8px 16px; border-bottom: 1px solid var(--border);
                   display: flex; align-items: center; gap: 8px; }
        .toolbar button { background: var(--btn-bg); color: var(--btn-fg); border: none;
                          padding: 5px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; }
        .toolbar button:hover { filter: brightness(1.15); }
        #status { margin-left: 12px; font-size: 12px; }
        .saved { color: var(--saved-fg); } .error { color: var(--error-fg); }

        .section-header { padding: 16px 16px 0; }
        h2 { font-size: 16px; color: var(--section-fg); padding: 4px 8px; border-radius: 4px;
             display: inline-block; cursor: text; border: 1px solid transparent; }
        h2:hover { border-color: var(--border); }
        h2:focus { outline: 2px solid var(--focus-border); border-color: transparent; }

        .table-wrap { padding: 8px 16px 16px; overflow-x: auto; }
        table { border-collapse: collapse; width: max-content; min-width: 100%; }
        th, td { border: 1px solid var(--border); padding: 6px 10px; text-align: left;
                 min-width: 100px; max-width: 400px; white-space: nowrap; overflow: hidden;
                 text-overflow: ellipsis; position: relative; }
        th { background: var(--hdr-bg); color: var(--hdr-fg); font-weight: 600;
             font-size: 12px; cursor: text; user-select: text; }
        th:focus { outline: 2px solid var(--focus-border); }

        .env-hdr { min-width: 110px; font-weight: 700; }
        .env-cell { font-weight: 600; background: var(--hdr-bg); min-width: 110px; position: relative; }
        .del-col { position: absolute; right: 3px; top: 2px; color: var(--del-fg);
                   cursor: pointer; font-size: 14px; opacity: 0; transition: opacity 0.15s; }
        th:hover .del-col { opacity: 0.7; }
        .del-col:hover { opacity: 1 !important; }
        .del-row { position: absolute; right: 3px; top: 50%; transform: translateY(-50%);
                   color: var(--del-fg); cursor: pointer; font-size: 14px; opacity: 0; transition: opacity 0.15s; }
        .env-cell:hover .del-row { opacity: 0.7; }
        .del-row:hover { opacity: 1 !important; }

        .add-col { background: var(--add-bg); color: var(--add-fg); font-size: 18px;
                   text-align: center; cursor: pointer; min-width: 40px; width: 40px; font-weight: 700; }
        .add-col:hover { filter: brightness(1.2); }
        .add-placeholder { border: none; background: transparent; min-width: 40px; width: 40px; }

        .add-row td { background: var(--add-bg); color: var(--add-fg); text-align: center;
                      cursor: pointer; font-size: 12px; padding: 4px; }
        .add-row td:hover { filter: brightness(1.2); }

        td[contenteditable] { cursor: text; }
        td[contenteditable]:focus { background: var(--focus-bg); outline: 2px solid var(--focus-border);
                                    outline-offset: -2px; white-space: normal; overflow: visible; }
        td.empty::before { content: '—'; color: var(--empty-fg); font-style: italic; }
        td.null-val { color: var(--null-fg); font-style: italic; }

        /* Modal */
        .modal-overlay { display:none; position:fixed; top:0; left:0; width:100%; height:100%;
                         background:rgba(0,0,0,0.5); z-index:1000; justify-content:center; align-items:center; }
        .modal-overlay.active { display:flex; }
        .modal { background:var(--bg); border:1px solid var(--border); border-radius:8px;
                 padding:20px; min-width:350px; box-shadow:0 4px 20px rgba(0,0,0,0.3); }
        .modal h3 { margin:0 0 12px; font-size:14px; color:var(--fg); }
        .modal input { width:100%; padding:6px 10px; font-size:13px; border:1px solid var(--border);
                       border-radius:4px; background:var(--hdr-bg); color:var(--fg); margin-bottom:12px; }
        .modal input:focus { outline:2px solid var(--focus-border); }
        .modal .modal-btns { display:flex; gap:8px; justify-content:flex-end; }
        .modal button { padding:5px 16px; border:none; border-radius:4px; cursor:pointer; font-size:12px; }
        .modal .btn-ok { background:var(--btn-bg); color:var(--btn-fg); }
        .modal .btn-cancel { background:var(--hdr-bg); color:var(--fg); border:1px solid var(--border); }
    """;

    // ========== JavaScript ==========

    private static final String JS = """
        function act(action) {
            if (window.__sct) window.__sct(action);
        }

        function onCellEdit(cell) {
            var s = cell.dataset.section, k = cell.dataset.key, e = cell.dataset.env;
            var v = cell.textContent.trim();
            cell.className = v === '' ? 'empty' : (v === 'null' ? 'null-val' : '');
            if (window.__sct) window.__sct('cell|' + s + '|' + k + '|' + e + '|' + v);
            showStatus('Unsaved changes', '');
        }

        function onCellFocus(cell) {
            // Clear placeholder for editing
            if (cell.classList.contains('empty')) cell.textContent = '';
        }

        function onSectionRename(h2) {
            var orig = h2.dataset.orig, val = h2.textContent.trim();
            if (val && val !== orig && window.__sct) {
                window.__sct('rename-section|' + orig + '|' + val);
                h2.dataset.orig = val;
                showStatus('Section renamed (unsaved)', '');
            }
        }

        function onKeyRename(th) {
            var section = th.dataset.section, orig = th.dataset.origKey;
            // Get text without the × button
            var val = '';
            for (var node of th.childNodes) {
                if (node.nodeType === 3) val += node.textContent;
            }
            val = val.trim();
            if (val && val !== orig && window.__sct) {
                window.__sct('rename-key|' + section + '|' + orig + '|' + val);
                th.dataset.origKey = val;
                showStatus('Key renamed (unsaved)', '');
            }
        }

        // ===== Modal helpers (replace prompt/confirm) =====
        var _modalCallback = null;
        var _confirmCallback = null;

        function showModal(title, defaultVal, callback) {
            _modalCallback = callback;
            document.getElementById('modal-title').textContent = title;
            var input = document.getElementById('modal-input');
            input.value = defaultVal || '';
            document.getElementById('modal').classList.add('active');
            setTimeout(function() { input.focus(); input.select(); }, 50);
        }
        function modalOk() {
            var val = document.getElementById('modal-input').value;
            document.getElementById('modal').classList.remove('active');
            if (_modalCallback) _modalCallback(val);
            _modalCallback = null;
        }
        function modalCancel() {
            document.getElementById('modal').classList.remove('active');
            _modalCallback = null;
        }
        function showConfirm(title, callback) {
            _confirmCallback = callback;
            document.getElementById('confirm-title').textContent = title;
            document.getElementById('confirm-modal').classList.add('active');
        }
        function confirmOk() {
            document.getElementById('confirm-modal').classList.remove('active');
            if (_confirmCallback) _confirmCallback();
            _confirmCallback = null;
        }
        function confirmCancel() {
            document.getElementById('confirm-modal').classList.remove('active');
            _confirmCallback = null;
        }
        // Enter key in modal input
        document.getElementById('modal-input').addEventListener('keydown', function(e) {
            if (e.key === 'Enter') { e.preventDefault(); modalOk(); }
            if (e.key === 'Escape') { e.preventDefault(); modalCancel(); }
        });

        // ===== Column / Row operations =====

        function onAddCol(section) {
            showModal('New property key:', '', function(key) {
                if (!key || !key.trim()) return;
                key = key.trim();

                var table = document.querySelector("table[data-section='" + section + "']");
                if (!table) return;

                var headerRow = table.querySelector('thead tr');
                var addTh = headerRow.querySelector('.add-col');
                var th = document.createElement('th');
                th.contentEditable = 'true';
                th.spellcheck = false;
                th.dataset.section = section;
                th.dataset.origKey = key;
                th.onblur = function() { onKeyRename(this); };
                th.innerHTML = key + "<span class='del-col' onclick='onDeleteCol(this)' title='Delete column'>×</span>";
                headerRow.insertBefore(th, addTh);

                var rows = table.querySelectorAll('tbody tr:not(.add-row)');
                rows.forEach(function(row) {
                    var envCell = row.querySelector('.env-cell');
                    var env = envCell ? envCell.textContent.replace('×','').trim() : '';
                    if (env === '_default') env = 'default';
                    var td = document.createElement('td');
                    td.className = 'empty';
                    td.contentEditable = 'true';
                    td.spellcheck = false;
                    td.dataset.section = section;
                    td.dataset.key = key;
                    td.dataset.env = env;
                    td.onblur = function() { onCellEdit(this); };
                    td.onfocus = function() { onCellFocus(this); };
                    var placeholder = row.querySelector('.add-placeholder');
                    row.insertBefore(td, placeholder);
                });

                if (window.__sct) window.__sct('add-col|' + section + '|' + key);
                showStatus('Column added (unsaved)', '');
            });
        }

        function onDeleteCol(span) {
            var th = span.parentElement;
            var section = th.dataset.section, key = th.dataset.origKey;
            showConfirm('Delete column "' + key + '"?', function() {
                var headerRow = th.parentElement;
                var idx = Array.from(headerRow.children).indexOf(th);
                th.remove();
                var table = headerRow.closest('table');
                table.querySelectorAll('tbody tr:not(.add-row)').forEach(function(row) {
                    if (row.children[idx]) row.children[idx].remove();
                });
                if (window.__sct) window.__sct('del-col|' + section + '|' + key);
                showStatus('Column deleted (unsaved)', '');
            });
        }

        function onAddEnv() {
            showModal('New environment name:', '', function(name) {
                if (!name || !name.trim()) return;
                name = name.trim();

                document.querySelectorAll('table').forEach(function(table) {
                    var section = table.dataset.section;
                    var headerCells = table.querySelectorAll('thead th');
                    var addRow = table.querySelector('.add-row');
                    var tr = document.createElement('tr');

                    var envTd = document.createElement('td');
                    envTd.className = 'env-cell';
                    envTd.innerHTML = name + "<span class='del-row' onclick=\"onDeleteRow('" + name + "')\" title='Delete'>×</span>";
                    tr.appendChild(envTd);

                    for (var i = 1; i < headerCells.length - 1; i++) {
                        var key = headerCells[i].dataset.origKey;
                        var td = document.createElement('td');
                        td.className = 'empty';
                        td.contentEditable = 'true';
                        td.spellcheck = false;
                        td.dataset.section = section;
                        td.dataset.key = key;
                        td.dataset.env = name;
                        td.onblur = function() { onCellEdit(this); };
                        td.onfocus = function() { onCellFocus(this); };
                        tr.appendChild(td);
                    }

                    var ph = document.createElement('td');
                    ph.className = 'add-placeholder';
                    tr.appendChild(ph);
                    addRow.parentElement.insertBefore(tr, addRow);
                });

                if (window.__sct) window.__sct('add-env|' + name);
                showStatus('Environment added (unsaved)', '');
            });
        }

        function onDeleteRow(env) {
            showConfirm('Delete environment "' + env + '"?', function() {
                document.querySelectorAll('table').forEach(function(table) {
                    table.querySelectorAll('tbody tr:not(.add-row)').forEach(function(row) {
                        var envCell = row.querySelector('.env-cell');
                        if (envCell && envCell.textContent.replace('×','').trim() === env) {
                            row.remove();
                        }
                    });
                });
                if (window.__sct) window.__sct('del-env|' + env);
                showStatus('Environment deleted (unsaved)', '');
            });
        }

        function showStatus(msg, cls) {
            var el = document.getElementById('status');
            if (el) { el.textContent = msg; el.className = cls; }
        }

        // Keyboard: Enter=commit, Tab=next cell, Escape=cancel
        document.addEventListener('keydown', function(e) {
            var t = e.target;
            if (!t.hasAttribute('contenteditable')) return;
            if (e.key === 'Enter') { e.preventDefault(); t.blur(); }
            if (e.key === 'Escape') { e.preventDefault(); t.blur(); }
            if (e.key === 'Tab') {
                e.preventDefault();
                var cells = Array.from(document.querySelectorAll('td[contenteditable]'));
                var idx = cells.indexOf(t);
                var next = e.shiftKey ? idx - 1 : idx + 1;
                if (next >= 0 && next < cells.length) { t.blur(); cells[next].focus(); }
            }
        });

        // Handle add-section from toolbar
        document.querySelector('.toolbar').addEventListener('click', function(e) {
            if (e.target.textContent.includes('Section')) {
                showModal('New section name:', '', function(name) {
                    if (name && window.__sct) window.__sct('add-section|' + name);
                });
            }
        });
    """;
}
