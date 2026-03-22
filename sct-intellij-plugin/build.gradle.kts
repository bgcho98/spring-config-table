plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.pinkmandarin"
version = "1.0.0-SNAPSHOT"

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
        description = "Generates per-environment Spring Boot YAML config files from a master Markdown table."
        vendor {
            name = "PinkMandarin"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
