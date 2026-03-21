package com.pinkmandarin.sct.plugin;

import com.pinkmandarin.sct.core.exporter.YamlExporter;
import com.pinkmandarin.sct.core.master.MasterMarkdownParser;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class GenerateYamlMojo extends AbstractMojo {

    @Parameter(property = "sct.masterFile", required = true)
    private File masterFile;

    @Parameter(property = "sct.outputDir", defaultValue = "${project.basedir}/src/main/resources")
    private File outputDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!masterFile.exists()) {
            throw new MojoExecutionException("Master config not found: " + masterFile.getAbsolutePath());
        }

        getLog().info("Generating YAML from: " + masterFile.getAbsolutePath());

        try {
            var result = new MasterMarkdownParser().parse(masterFile.toPath());
            new YamlExporter().exportAll(result.properties(), result.environments(), outputDir.toPath());
            getLog().info("Generated " + result.environments().size() + " YAML files.");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate YAML files", e);
        }
    }
}
