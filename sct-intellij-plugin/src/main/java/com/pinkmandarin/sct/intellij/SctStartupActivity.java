package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.Alarm;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Watches for master Markdown file changes via IntelliJ VFS events
 * and triggers automatic YAML regeneration with debounce.
 */
public class SctStartupActivity implements ProjectActivity {

    private static final int DEBOUNCE_MS = 500;

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        SctFileWatcher.getInstance(project).refreshCache();

        var disposable = Disposer.newDisposable("sct-vfs-listener");
        Disposer.register(project, disposable);

        var alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable);

        project.getMessageBus().connect(disposable).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (!SctSettings.getInstance(project).isAutoGenerate()) return;

                var masterPaths = SctFileWatcher.getInstance(project).getMasterPaths();
                var matchedPaths = new java.util.LinkedHashSet<String>();

                for (var event : events) {
                    String filePath = resolveFilePath(event);
                    if (filePath != null && masterPaths.contains(filePath)) {
                        matchedPaths.add(filePath);
                    }
                }

                if (!matchedPaths.isEmpty()) {
                    alarm.cancelAllRequests();
                    alarm.addRequest(() -> {
                        for (var path : matchedPaths) {
                            SctGenerator.generateByFilePath(project, path);
                        }
                    }, DEBOUNCE_MS);
                }
            }
        });
        return Unit.INSTANCE;
    }

    private static String resolveFilePath(VFileEvent event) {
        if (event instanceof VFileContentChangeEvent e) {
            return e.getFile().getPath();
        }
        if (event instanceof VFileCreateEvent e) {
            return Path.of(e.getParent().getPath(), e.getChildName()).toString();
        }
        if (event instanceof VFileMoveEvent e) {
            return e.getFile().getPath();
        }
        if (event instanceof VFilePropertyChangeEvent e && VirtualFile.PROP_NAME.equals(e.getPropertyName())) {
            return e.getFile().getPath();
        }
        return null;
    }
}
