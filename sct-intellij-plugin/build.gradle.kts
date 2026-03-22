plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.pinkmandarin"
version = "1.0.0"

val sctCoreVersion: String by project

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
    }

    implementation("com.pinkmandarin:sct-core:$sctCoreVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        id = "com.pinkmandarin.sct"
        name = "Spring Config Table"
        version = project.version.toString()
        description = """
            <p>Manage Spring Boot multi-environment YAML config files using a single Markdown table.</p>
            <ul>
                <li><b>Visual Table Editor</b> — Master-Detail layout with grouped property list, search filter, and inline editing for all environments</li>
                <li><b>YAML Lens</b> — View multiple YAML/Markdown files as a searchable property table with CSV export</li>
                <li><b>YAML ↔ Markdown Migration</b> — Convert existing YAML files to master Markdown (with comments) and back</li>
                <li><b>Auto-Generation</b> — Automatically regenerates per-environment YAML files when the master Markdown changes</li>
                <li><b>Spring Metadata</b> — Type detection and auto-completion from spring-configuration-metadata.json</li>
                <li><b>Comment Round-Trip</b> — YAML inline comments preserved as HTML comments in Markdown</li>
                <li><b>Multi-Module</b> — Configure different master files and output paths per module</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "PinkMandarin"
            url = "https://github.com/pinkmandarin/spring-config-table"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }

    pluginConfiguration {
        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Master Markdown → per-environment YAML generation</li>
                <li>YAML → Markdown migration with comment preservation</li>
                <li>Visual table editor with Master-Detail layout and grouped property list</li>
                <li>YAML Lens viewer with search, filter, natural sort, CSV export</li>
                <li>Spring Boot metadata integration for type detection</li>
                <li>Configurable environment sorting (lifecycle × region)</li>
                <li>Auto-generation on file change with debounce</li>
                <li>Multi-module project support</li>
                <li>i18n: English + Korean</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
