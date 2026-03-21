package com.pinkmandarin.sct.core.importer;

import com.pinkmandarin.sct.core.model.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlImporterTest {

    @TempDir
    Path tempDir;

    @Test
    void importDirectory_defaultOnly() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        assertThat(result.environments()).hasSize(1);
        assertThat(result.environments().get(0).name()).isEqualTo("default");
        assertThat(result.properties()).isNotEmpty();

        var port = result.properties().stream()
                .filter(p -> "server".equals(p.section()) && "port".equals(p.key()))
                .findFirst().orElseThrow();
        assertThat(port.value()).isEqualTo("8080");
    }

    @Test
    void importDirectory_profileOverride() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                  host: localhost
                """);
        Files.writeString(tempDir.resolve("application-beta.yml"), """
                server:
                  port: 9090
                  host: localhost
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        assertThat(result.environments()).extracting(Environment::name)
                .containsExactly("default", "beta");

        // host is same as default -> filtered out
        var betaProps = result.properties().stream()
                .filter(p -> "beta".equals(p.env())).toList();
        assertThat(betaProps).hasSize(1);
        assertThat(betaProps.get(0).key()).isEqualTo("port");
        assertThat(betaProps.get(0).value()).isEqualTo("9090");
    }

    @Test
    void importDirectory_removesOnProfile() throws Exception {
        Files.writeString(tempDir.resolve("application-beta.yml"), """
                spring:
                  config:
                    activate:
                      on-profile: beta
                server:
                  port: 9090
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        var springProps = result.properties().stream()
                .filter(p -> "spring".equals(p.section()))
                .toList();
        assertThat(springProps).isEmpty();
    }

    @Test
    void importDirectory_emptyYaml() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), "");

        var result = new YamlImporter().importDirectory(tempDir);
        assertThat(result.environments()).hasSize(1);
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void importDirectory_dotInYamlKey_escaped() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                spring:
                  supporter.add: true
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        var prop = result.properties().stream()
                .filter(p -> "spring".equals(p.section()))
                .findFirst().orElseThrow();
        assertThat(prop.key()).isEqualTo("supporter\\.add");
        assertThat(prop.value()).isEqualTo("true");
    }

    @Test
    void importDirectory_scalarYaml_notMap() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), "just a string");

        var result = new YamlImporter().importDirectory(tempDir);
        assertThat(result.environments()).hasSize(1);
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void importDirectory_nestedListWithMaps() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                app:
                  endpoints:
                    - url: http://a
                      timeout: 1000
                    - url: http://b
                      timeout: 2000
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        var endpointProps = result.properties().stream()
                .filter(p -> p.key().startsWith("endpoints[")).toList();
        assertThat(endpointProps).hasSize(4);
        assertThat(endpointProps.stream().map(p -> p.key()).toList())
                .contains("endpoints[0].url", "endpoints[0].timeout", "endpoints[1].url", "endpoints[1].timeout");
    }

    @Test
    void importDirectory_multiDocumentYaml() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                ---
                logging:
                  level: DEBUG
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        assertThat(result.properties()).hasSizeGreaterThanOrEqualTo(2);
        var sections = result.properties().stream().map(p -> p.section()).distinct().toList();
        assertThat(sections).contains("server", "logging");
    }

    @Test
    void importDirectory_manyProfiles() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), "server:\n  port: 8080\n");
        for (var profile : List.of("alpha", "beta", "real", "dr", "gov")) {
            Files.writeString(tempDir.resolve("application-" + profile + ".yml"),
                    "server:\n  port: " + (9000 + profile.length()) + "\n");
        }

        var result = new YamlImporter().importDirectory(tempDir);

        assertThat(result.environments()).hasSize(6); // default + 5 profiles
        assertThat(result.properties().stream().filter(p -> !"default".equals(p.env())).count()).isEqualTo(5);
    }

    @Test
    void importDirectory_topLevelScalar_roundTrip() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                debug: true
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        var debugProp = result.properties().stream()
                .filter(p -> "debug".equals(p.section())).findFirst().orElseThrow();
        assertThat(debugProp.key()).isEqualTo("__scalar__");

        // Verify it exports back correctly
        var outputDir = tempDir.resolve("output");
        new com.pinkmandarin.sct.core.exporter.YamlExporter().exportAll(result.properties(), result.environments(), outputDir);
        var yaml = Files.readString(outputDir.resolve("application.yml"));
        assertThat(yaml).contains("debug: true");
        assertThat(yaml).doesNotContain("__scalar__");
    }

    @Test
    void importDirectory_ignoresNonApplicationFiles() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), "server:\n  port: 8080\n");
        Files.writeString(tempDir.resolve("applicationFOO.yml"), "bad:\n  data: true\n");
        Files.writeString(tempDir.resolve("other.yml"), "other:\n  key: val\n");

        var result = new YamlImporter().importDirectory(tempDir);

        var sections = result.properties().stream().map(p -> p.section()).distinct().toList();
        assertThat(sections).containsExactly("server");
        assertThat(sections).doesNotContain("bad", "other");
    }

    @Test
    void importDirectory_listValues() throws Exception {
        Files.writeString(tempDir.resolve("application.yml"), """
                app:
                  servers:
                    - host1
                    - host2
                """);

        var result = new YamlImporter().importDirectory(tempDir);

        var listProps = result.properties().stream()
                .filter(p -> p.key().startsWith("servers[")).toList();
        assertThat(listProps).hasSize(2);
    }
}
