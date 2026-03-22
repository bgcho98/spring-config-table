package com.pinkmandarin.sct.intellij;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "SctSettings", storages = @Storage("sct.xml"))
public final class SctSettings implements PersistentStateComponent<SctSettings.State> {

    public static final String DEFAULT_ENV_ORDER = "default, local, dev, alpha, beta, real, release, dr, gov";

    private State state = new State();

    public static SctSettings getInstance(@NotNull Project project) {
        return project.getService(SctSettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public List<ModuleMapping> getMappings() {
        return List.copyOf(state.mappings);
    }

    public void setMappings(List<ModuleMapping> mappings) {
        state.mappings = new ArrayList<>(mappings);
    }

    public boolean isAutoGenerate() {
        return state.autoGenerate;
    }

    public void setAutoGenerate(boolean autoGenerate) {
        state.autoGenerate = autoGenerate;
    }

    public String getEnvOrder() {
        return state.envOrder;
    }

    public void setEnvOrder(String envOrder) {
        state.envOrder = envOrder;
    }

    /** Parse the comma-separated envOrder string into a list */
    public List<String> getEnvOrderList() {
        var order = state.envOrder;
        if (order == null || order.isBlank()) order = DEFAULT_ENV_ORDER;
        var result = new ArrayList<String>();
        for (var s : order.split(",")) {
            var trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    @Tag("module-mapping")
    public static class ModuleMapping {
        public String masterFile = "master-config.md";
        public String outputDir = "src/main/resources";

        public ModuleMapping() {}

        public ModuleMapping(String masterFile, String outputDir) {
            this.masterFile = masterFile;
            this.outputDir = outputDir;
        }

        public ModuleMapping copy() {
            return new ModuleMapping(masterFile, outputDir);
        }
    }

    public static class State {
        @XCollection(elementTypes = ModuleMapping.class)
        public List<ModuleMapping> mappings = new ArrayList<>(List.of(new ModuleMapping()));
        public boolean autoGenerate = true;
        public String envOrder = DEFAULT_ENV_ORDER;
    }
}
