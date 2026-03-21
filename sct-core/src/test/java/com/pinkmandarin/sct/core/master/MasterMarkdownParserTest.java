package com.pinkmandarin.sct.core.master;

import com.pinkmandarin.sct.core.model.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MasterMarkdownParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_basicTable() throws Exception {
        var md = """
                ## server

                | env | port | host |
                |-----|------|------|
                | _default | 8080 | localhost |
                | beta | 9090 | |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(3);
        assertThat(result.environments()).extracting(Environment::name)
                .containsExactly("default", "beta");
    }

    @Test
    void parse_sectionWithPrefix() throws Exception {
        var md = """
                ## spring.datasource

                | env | url | username |
                |-----|-----|----------|
                | _default | jdbc:mysql://localhost | root |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(2);
        var url = result.properties().stream()
                .filter(p -> "spring".equals(p.section()) && "datasource.url".equals(p.key()))
                .findFirst().orElseThrow();
        assertThat(url.value()).isEqualTo("jdbc:mysql://localhost");
    }

    @Test
    void parse_escapedPipe() throws Exception {
        var md = """
                ## app

                | env | pattern |
                |-----|---------|
                | _default | a\\|b\\|c |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("a|b|c");
    }

    @Test
    void parse_emptyCellsInherited() throws Exception {
        var md = """
                ## server

                | env | port | host |
                |-----|------|------|
                | _default | 8080 | localhost |
                | beta | 9090 | |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        // beta has port but NOT host (empty = inherit)
        var betaProps = result.properties().stream()
                .filter(p -> "beta".equals(p.env())).toList();
        assertThat(betaProps).hasSize(1);
        assertThat(betaProps.get(0).key()).isEqualTo("port");
    }

    @Test
    void parse_nullValue() throws Exception {
        var md = """
                ## app

                | env | key |
                |-----|-----|
                | _default | null |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).isNullValue()).isTrue();
    }

    @Test
    void parse_quotedString() throws Exception {
        var md = """
                ## app

                | env | phone |
                |-----|-------|
                | _default | "01012345678" |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("01012345678");
        assertThat(result.properties().get(0).valueType()).isEqualTo("string");
    }

    @Test
    void parse_emptyFile() throws Exception {
        var file = writeFile("");
        var result = new MasterMarkdownParser().parse(file);
        assertThat(result.properties()).isEmpty();
        assertThat(result.environments()).hasSize(1); // default always present
    }

    @Test
    void parse_headingWithoutTable_skipped() throws Exception {
        var md = """
                ## orphan

                some text without table

                ## server

                | env | port |
                |-----|------|
                | _default | 8080 |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).section()).isEqualTo("server");
    }

    @Test
    void parse_multipleSections() throws Exception {
        var md = """
                ## server

                | env | port |
                |-----|------|
                | _default | 8080 |

                ## spring.datasource

                | env | url |
                |-----|-----|
                | _default | jdbc:mysql://localhost |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(2);
        var sections = result.properties().stream().map(p -> p.section()).distinct().toList();
        assertThat(sections).containsExactlyInAnyOrder("server", "spring");
    }

    @Test
    void parse_deepPrefix() throws Exception {
        var md = """
                ## spring.jpa.hibernate

                | env | ddl-auto |
                |-----|----------|
                | _default | update |
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).section()).isEqualTo("spring");
        assertThat(result.properties().get(0).key()).isEqualTo("jpa.hibernate.ddl-auto");
    }

    @Test
    void parse_noTrailingPipe() throws Exception {
        var md = """
                ## server

                | env | port
                |-----|-----
                | _default | 8080
                """;

        var file = writeFile(md);
        var result = new MasterMarkdownParser().parse(file);

        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().get(0).value()).isEqualTo("8080");
    }

    private Path writeFile(String content) throws Exception {
        var file = tempDir.resolve("test.md");
        Files.writeString(file, content);
        return file;
    }
}
