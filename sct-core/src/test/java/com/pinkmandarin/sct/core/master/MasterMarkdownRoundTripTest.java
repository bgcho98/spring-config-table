package com.pinkmandarin.sct.core.master;

import com.pinkmandarin.sct.core.exporter.YamlExporter;
import com.pinkmandarin.sct.core.model.Environment;
import com.pinkmandarin.sct.core.model.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MasterMarkdownRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTrip_simpleProperties() throws Exception {
        var props = List.of(
                Property.of("server", "port", "default", 8080),
                Property.of("server", "port", "beta", 8081),
                Property.of("spring", "datasource.url", "default", "jdbc:mysql://localhost/db")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);

        assertThat(result.properties()).hasSize(3);
        assertThat(result.environments()).extracting(Environment::name)
                .contains("default", "beta");

        var serverPort = result.properties().stream()
                .filter(p -> "server".equals(p.section()) && "port".equals(p.key()) && "default".equals(p.env()))
                .findFirst().orElseThrow();
        assertThat(serverPort.value()).isEqualTo("8080");
        assertThat(serverPort.valueType()).isEqualTo("int");
    }

    @Test
    void roundTrip_pipeInValue() throws Exception {
        var props = List.of(
                Property.of("app", "pattern", "default", "a|b|c")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var content = Files.readString(mdFile);
        assertThat(content).contains("a\\|b\\|c");

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("a|b|c");
    }

    @Test
    void roundTrip_newlineInValue() throws Exception {
        var props = List.of(
                Property.of("app", "message", "default", "line1\nline2")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("line1\nline2");
    }

    @Test
    void roundTrip_nullValue() throws Exception {
        var props = List.of(
                Property.of("app", "key", "default", null)
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).isNullValue()).isTrue();
    }

    @Test
    void roundTrip_booleanValue() throws Exception {
        var props = List.of(
                Property.of("app", "enabled", "default", true),
                Property.of("app", "debug", "default", false)
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(2);

        var enabled = result.properties().stream()
                .filter(p -> "enabled".equals(p.key())).findFirst().orElseThrow();
        assertThat(enabled.valueType()).isEqualTo("bool");
        assertThat(enabled.value()).isEqualTo("true");
    }

    @Test
    void roundTrip_zeroPrefix_quotedString() throws Exception {
        var props = List.of(
                Property.of("app", "phone", "default", "01012345678")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var content = Files.readString(mdFile);
        assertThat(content).contains("\"01012345678\"");

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("01012345678");
        assertThat(result.properties().get(0).valueType()).isEqualTo("string");
    }

    @Test
    void roundTrip_multipleEnvironments() throws Exception {
        var props = List.of(
                Property.of("server", "port", "default", 8080),
                Property.of("server", "port", "alpha", 8081),
                Property.of("server", "port", "beta", 8082),
                Property.of("server", "port", "real", 8083)
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.environments()).hasSize(4);
        assertThat(result.properties()).hasSize(4);
    }

    @Test
    void roundTrip_backslashN_literalNotNewline() throws Exception {
        // Value contains literal \n (backslash + n), NOT a newline
        var props = List.of(
                Property.of("app", "regex", "default", "line\\nbreak")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("line\\nbreak");
    }

    @Test
    void roundTrip_stringTrue_notConvertedToBoolean() throws Exception {
        var props = List.of(
                Property.of("app", "flag", "default", "true")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var content = Files.readString(mdFile);
        assertThat(content).contains("\"true\"");

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("true");
        assertThat(result.properties().get(0).valueType()).isEqualTo("string");
    }

    @Test
    void roundTrip_stringNumber_notConvertedToInt() throws Exception {
        var props = List.of(
                Property.of("app", "code", "default", "42")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var content = Files.readString(mdFile);
        assertThat(content).contains("\"42\"");

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("42");
        assertThat(result.properties().get(0).valueType()).isEqualTo("string");
    }

    @Test
    void roundTrip_stringNull_notConvertedToNull() throws Exception {
        var props = List.of(
                Property.of("app", "val", "default", "null")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("null");
        assertThat(result.properties().get(0).valueType()).isEqualTo("string");
        assertThat(result.properties().get(0).isNullValue()).isFalse();
    }

    @Test
    void roundTrip_valueWithLiteralQuotes() throws Exception {
        var props = List.of(
                Property.of("app", "greeting", "default", "say \"hello\"")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("say \"hello\"");
    }

    @Test
    void roundTrip_valueWithLeadingTrailingSpaces() throws Exception {
        var props = List.of(
                Property.of("app", "padded", "default", "  hello  ")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("  hello  ");
    }

    @Test
    void roundTrip_backslashInPath() throws Exception {
        var props = List.of(
                Property.of("app", "path", "default", "C:\\new\\folder")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var result = new MasterMarkdownParser().parse(mdFile);
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("C:\\new\\folder");
    }

    @Test
    void fullPipeline_markdownToYaml() throws Exception {
        var props = List.of(
                Property.of("server", "port", "default", 8080),
                Property.of("server", "port", "beta", 9090),
                Property.of("spring", "datasource.url", "default", "jdbc:mysql://localhost/db"),
                Property.of("spring", "datasource.url", "beta", "jdbc:mysql://beta/db")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var parsed = new MasterMarkdownParser().parse(mdFile);

        var outputDir = tempDir.resolve("output");
        new YamlExporter().exportAll(parsed.properties(), parsed.environments(), outputDir);

        assertThat(outputDir.resolve("application.yml")).exists();
        assertThat(outputDir.resolve("application-beta.yml")).exists();

        var defaultYaml = Files.readString(outputDir.resolve("application.yml"));
        assertThat(defaultYaml).contains("port: 8080");

        var betaYaml = Files.readString(outputDir.resolve("application-beta.yml"));
        assertThat(betaYaml).contains("port: 9090");
        assertThat(betaYaml).contains("on-profile: beta");
    }

    @Test
    void roundTrip_commentsPreserved_mdToYaml() throws Exception {
        var props = List.of(
                Property.of("server", "port", "default", 8080, "HTTP server port"),
                Property.of("server", "host", "default", "localhost", "bind address"),
                Property.of("server", "port", "beta", 9090, "beta port")
        );

        // Write to Markdown
        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        // Verify Markdown has HTML comments
        var mdContent = Files.readString(mdFile);
        assertThat(mdContent).contains("<!-- HTTP server port -->");
        assertThat(mdContent).contains("<!-- bind address -->");

        // Parse back from Markdown
        var parsed = new MasterMarkdownParser().parse(mdFile);
        assertThat(parsed.properties()).isNotEmpty();

        var portDefault = parsed.properties().stream()
                .filter(p -> "port".equals(p.key()) && "default".equals(p.env()))
                .findFirst().orElseThrow();
        assertThat(portDefault.comment()).isEqualTo("HTTP server port");

        // Export to YAML
        var outputDir = tempDir.resolve("output");
        new YamlExporter().exportAll(parsed.properties(), parsed.environments(), outputDir);

        var defaultYaml = Files.readString(outputDir.resolve("application.yml"));
        assertThat(defaultYaml).contains("port: 8080 # HTTP server port");
        assertThat(defaultYaml).contains("host: localhost # bind address");

        var betaYaml = Files.readString(outputDir.resolve("application-beta.yml"));
        assertThat(betaYaml).contains("port: 9090 # beta port");
    }

    @Test
    void roundTrip_commentsPreserved_nestedKeys() throws Exception {
        var props = List.of(
                Property.of("spring", "datasource.url", "default", "jdbc:mysql://localhost/db", "DB connection URL"),
                Property.of("spring", "datasource.username", "default", "root", "DB user")
        );

        var mdFile = tempDir.resolve("master.md");
        new MasterMarkdownWriter().write(props, mdFile);

        var parsed = new MasterMarkdownParser().parse(mdFile);

        var outputDir = tempDir.resolve("output");
        new YamlExporter().exportAll(parsed.properties(), parsed.environments(), outputDir);

        var yaml = Files.readString(outputDir.resolve("application.yml"));
        assertThat(yaml).contains("url: jdbc:mysql://localhost/db # DB connection URL");
        assertThat(yaml).contains("username: root # DB user");
    }
}
