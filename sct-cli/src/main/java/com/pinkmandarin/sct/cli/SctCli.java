package com.pinkmandarin.sct.cli;

import com.pinkmandarin.sct.core.exporter.YamlExporter;
import com.pinkmandarin.sct.core.importer.YamlImporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import com.pinkmandarin.sct.core.master.MasterMarkdownWriter;

import java.nio.file.Path;

public class SctCli {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        try {
            switch (args[0]) {
                case "migrate" -> runMigrate(args);
                case "generate" -> runGenerate(args);
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Existing YAML files -> master Markdown (one-time migration)
    private static void runMigrate(String[] args) throws Exception {
        var inputDir = getOption(args, "-i");
        var output = getOption(args, "-o");
        if (inputDir == null || output == null) {
            System.err.println("Usage: migrate -i <yaml-dir> -o <output.md>");
            System.exit(1);
        }

        var result = new YamlImporter().importDirectory(Path.of(inputDir));
        new MasterMarkdownWriter().write(result.properties(), Path.of(output));
        System.out.println("Migration completed: " + output);
    }

    // Master Markdown -> per-environment YAML files
    private static void runGenerate(String[] args) throws Exception {
        var input = getOption(args, "-i");
        var outputDir = getOption(args, "-o");
        if (input == null || outputDir == null) {
            System.err.println("Usage: generate -i <master.md> -o <output-dir>");
            System.exit(1);
        }

        var result = new MasterMarkdownParser().parse(Path.of(input));
        new YamlExporter().exportAll(result.properties(), result.environments(), Path.of(outputDir));
        System.out.println("Generated " + result.environments().size() + " YAML files to: " + outputDir);
    }

    private static String getOption(String[] args, String opt) {
        for (int i = 0; i < args.length - 1; i++) {
            if (opt.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    private static void printUsage() {
        System.out.println("""
                sct - Spring Config Table CLI

                Commands:
                  migrate   -i <yaml-dir> -o <output.md>     Migrate existing YAML files to master Markdown
                  generate  -i <master.md> -o <output-dir>   Generate per-environment YAML from master Markdown
                """);
    }
}
