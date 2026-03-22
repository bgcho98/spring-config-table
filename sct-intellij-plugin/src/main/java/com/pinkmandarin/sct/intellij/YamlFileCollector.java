package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects YAML and Markdown files from action event data.
 * Supports: multiple file selection, single file, directory (scans children).
 */
final class YamlFileCollector {

    private YamlFileCollector() {}

    static List<VirtualFile> collect(AnActionEvent e) {
        var result = new ArrayList<VirtualFile>();

        var files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files == null) {
            files = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
        }

        if (files != null) {
            for (var file : files) {
                collectFrom(file, result);
            }
        }

        // Fallback: single file
        if (result.isEmpty()) {
            var single = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (single != null) {
                collectFrom(single, result);
            }
        }

        return result;
    }

    private static void collectFrom(VirtualFile file, List<VirtualFile> result) {
        if (file.isDirectory()) {
            var children = file.getChildren();
            if (children != null) {
                for (var child : children) {
                    if (!child.isDirectory() && isSupportedFile(child)) {
                        result.add(child);
                    }
                }
            }
        } else if (isSupportedFile(file)) {
            result.add(file);
        }
    }

    static boolean isYaml(VirtualFile file) {
        var name = file.getName();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    static boolean isMarkdown(VirtualFile file) {
        return file.getName().endsWith(".md");
    }

    private static boolean isSupportedFile(VirtualFile file) {
        return isYaml(file) || isMarkdown(file);
    }
}
