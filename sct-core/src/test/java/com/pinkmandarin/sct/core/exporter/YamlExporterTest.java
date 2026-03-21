package com.pinkmandarin.sct.core.exporter;

import com.pinkmandarin.sct.core.model.Property;
import org.junit.jupiter.api.Test;

import com.pinkmandarin.sct.core.model.Environment;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void castValue_boolean() {
        assertThat(YamlExporter.castValue("true", "bool")).isEqualTo(true);
        assertThat(YamlExporter.castValue("false", "bool")).isEqualTo(false);
    }

    @Test
    void castValue_int() {
        assertThat(YamlExporter.castValue("42", "int")).isEqualTo(42);
    }

    @Test
    void castValue_long() {
        assertThat(YamlExporter.castValue("3000000000", "int")).isEqualTo(3000000000L);
    }

    @Test
    void castValue_float() {
        assertThat(YamlExporter.castValue("3.14", "float")).isEqualTo(3.14);
    }

    @Test
    void castValue_null() {
        assertThat(YamlExporter.castValue(null, "string")).isNull();
        assertThat(YamlExporter.castValue(Property.NULL_VALUE, "null")).isNull();
    }

    @Test
    void castValue_malformedNumber_fallsBackToString() {
        assertThat(YamlExporter.castValue("not-a-number", "int")).isEqualTo("not-a-number");
        assertThat(YamlExporter.castValue("not-a-float", "float")).isEqualTo("not-a-float");
    }

    @Test
    void castValue_string() {
        assertThat(YamlExporter.castValue("hello", "string")).isEqualTo("hello");
    }

    @Test
    void unflatten_simpleNested() {
        var props = List.of(
                Property.of("spring", "datasource.url", "default", "jdbc:mysql://localhost/db"),
                Property.of("spring", "datasource.username", "default", "root")
        );

        var result = YamlExporter.unflatten(props);

        @SuppressWarnings("unchecked")
        var spring = (Map<String, Object>) result.get("spring");
        @SuppressWarnings("unchecked")
        var ds = (Map<String, Object>) spring.get("datasource");
        assertThat(ds.get("url")).isEqualTo("jdbc:mysql://localhost/db");
        assertThat(ds.get("username")).isEqualTo("root");
    }

    @Test
    void unflatten_listValues() {
        var props = List.of(
                Property.of("app", "servers[0]", "default", "host1"),
                Property.of("app", "servers[1]", "default", "host2")
        );

        var result = YamlExporter.unflatten(props);

        @SuppressWarnings("unchecked")
        var app = (Map<String, Object>) result.get("app");
        @SuppressWarnings("unchecked")
        var servers = (List<Object>) app.get("servers");
        assertThat(servers).containsExactly("host1", "host2");
    }

    @Test
    void exportAll_createsOutputDir() throws Exception {
        var outputDir = tempDir.resolve("nested/output");
        var props = List.of(Property.of("server", "port", "default", 8080));
        var envs = List.of(new Environment("default"));

        new YamlExporter().exportAll(props, envs, outputDir);

        assertThat(outputDir).exists();
        assertThat(outputDir.resolve("application.yml")).exists();
    }

    @Test
    void exportAll_skipsEmptyEnv() throws Exception {
        var outputDir = tempDir.resolve("output");
        var props = List.of(Property.of("server", "port", "default", 8080));
        var envs = List.of(new Environment("default"), new Environment("beta"));

        new YamlExporter().exportAll(props, envs, outputDir);

        assertThat(outputDir.resolve("application.yml")).exists();
        assertThat(outputDir.resolve("application-beta.yml")).doesNotExist();
    }

    @Test
    void buildYamlForEnv_profileActivation() {
        var props = List.of(Property.of("server", "port", "beta", 9090));
        var yaml = new YamlExporter().buildYamlForEnv(props, "beta");

        assertThat(yaml).contains("on-profile: beta");
        // spring.config.activate should come first
        assertThat(yaml.indexOf("spring:")).isLessThan(yaml.indexOf("server:"));
    }

    @Test
    void buildYamlForEnv_defaultNoProfileActivation() {
        var props = List.of(Property.of("server", "port", "default", 8080));
        var yaml = new YamlExporter().buildYamlForEnv(props, "default");

        assertThat(yaml).doesNotContain("on-profile");
    }

    @Test
    void buildYamlForEnv_emptyProps_returnsNull() {
        assertThat(new YamlExporter().buildYamlForEnv(List.of(), "default")).isNull();
    }

    @Test
    void unflatten_escapedDot_preservedAsLiteralDot() {
        var props = List.of(Property.of("spring", "supporter\\.add", "default", true));
        var result = YamlExporter.unflatten(props);

        @SuppressWarnings("unchecked")
        var spring = (Map<String, Object>) result.get("spring");
        assertThat(spring).containsKey("supporter.add");
        assertThat(spring.get("supporter.add")).isEqualTo(true);
    }

    @Test
    void unflatten_conflictingPath_throwsIllegalState() {
        // "app.name" is a string, but "app.name.first" tries to nest into it
        var props = List.of(
                Property.of("app", "name", "default", "value"),
                Property.of("app", "name.first", "default", "nested")
        );

        assertThatThrownBy(() -> YamlExporter.unflatten(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting property path");
    }
}
