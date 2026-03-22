package com.pinkmandarin.sct.core.master;

import com.pinkmandarin.sct.core.model.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MasterMarkdownWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void write_envSorted_defaultFirst() throws Exception {
        var props = List.of(
                Property.of("server", "port", "real", 80),
                Property.of("server", "port", "default", 8080),
                Property.of("server", "port", "alpha", 8081)
        );

        var file = tempDir.resolve("out.md");
        new MasterMarkdownWriter().write(props, file);

        var lines = Files.readAllLines(file);
        var dataLines = lines.stream().filter(l -> l.startsWith("| ") && !l.startsWith("| env") && !l.startsWith("| ---")).toList();
        assertThat(dataLines.get(0)).startsWith("| _default");
        assertThat(dataLines.get(1)).startsWith("| alpha");
        assertThat(dataLines.get(2)).startsWith("| real");
    }

    @Test
    void write_groupedTables_moreThan10Columns() throws Exception {
        var props = new ArrayList<Property>();
        for (int i = 0; i < 12; i++) {
            props.add(Property.of("app", "group.key" + i, "default", "val" + i));
        }

        var file = tempDir.resolve("out.md");
        new MasterMarkdownWriter().write(props, file);

        var content = Files.readString(file);
        // Should have grouped heading like ## app.group
        assertThat(content).contains("## app.group");
    }

    @Test
    void write_exactly10Columns_singleTable() throws Exception {
        var props = new ArrayList<Property>();
        for (int i = 0; i < 10; i++) {
            props.add(Property.of("app", "group.key" + i, "default", "val" + i));
        }

        var file = tempDir.resolve("out.md");
        new MasterMarkdownWriter().write(props, file);

        var content = Files.readString(file);
        // 10 keys = single table, no grouped heading
        assertThat(content).contains("## app");
        assertThat(content).doesNotContain("## app.group");
    }

    @Test
    void write_emptyProperties() throws Exception {
        var file = tempDir.resolve("out.md");
        new MasterMarkdownWriter().write(List.of(), file);

        var content = Files.readString(file);
        assertThat(content).contains("# Application Properties");
        assertThat(content).doesNotContain("## ");
    }

    @Test
    void write_separatorLine() throws Exception {
        var props = List.of(Property.of("server", "port", "default", 8080));

        var file = tempDir.resolve("out.md");
        new MasterMarkdownWriter().write(props, file);

        var content = Files.readString(file);
        assertThat(content).contains("| --- |");
    }
}
