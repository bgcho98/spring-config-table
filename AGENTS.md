# AGENTS.md

## Project Overview

Spring Config Table (SCT) - A tool that manages Spring Boot multi-environment YAML config files using Markdown tables. One master Markdown file contains all environment settings in a human-readable table format, and the tool generates per-environment `application-{profile}.yml` files.

## Architecture

```
sct-core/         Core library (parser, writer, exporter, importer)
sct-cli/          CLI tool (fat JAR)
sct-maven-plugin/ Maven plugin (generate-resources phase)
sct-intellij-plugin/ IntelliJ IDEA plugin (Gradle-based, separate from Maven)
```

### Data Flow

```
[Existing YAML files] --migrate--> [Master Markdown] --generate--> [Per-env YAML files]
```

### Key Classes

- `MasterMarkdownParser` - Parses Markdown tables into `ParseResult(List<Property>, List<Environment>)`
- `MasterMarkdownWriter` - Writes `List<Property>` to Markdown table format
- `YamlExporter` - Converts `List<Property>` to per-environment YAML files via SnakeYAML
- `YamlImporter` - Imports existing Spring Boot YAML files (supports multi-document `---`)
- `Property(section, key, env, value, valueType)` - Core data model (immutable record)
- `Environment(name)` - Environment identifier with display name conversion (`default` <-> `_default`)

## Build

```bash
# Maven modules (core, cli, maven-plugin)
mvn clean install

# IntelliJ plugin (requires sct-core in mavenLocal)
cd sct-intellij-plugin && ./gradlew buildPlugin
```

## Test

```bash
mvn -pl sct-core test    # 63 tests
```

## Coding Conventions

- Java 21, using `var`, records, switch expressions, pattern matching
- Package: `com.pinkmandarin.sct.*`
- No Lombok - use Java records for data classes
- SnakeYAML with `SafeConstructor` (security)
- IntelliJ plugin: `DynamicBundle` for i18n, `Alarm` for debounce, `@Service(Service.Level.PROJECT)` for project-scoped services

## Important Design Decisions

### Escape Protocol (Markdown table cells)

Writer escapes in this order: `\` -> `\\`, `\n` -> `\\n`, `|` -> `\|`, then quotes if needed.
Parser unescapes: `\n` -> newline, `\\` -> `\`, `\"` -> `"` (inside quotes only).
Pipe (`\|`) is handled during cell splitting in `parseTableRow()`, not in `unescapeCellValue()`.

### Type Preservation

`Property.valueType` preserves original types (`bool`, `int`, `float`, `string`, `null`).
Writer quotes string values that look like boolean/number/null to prevent type coercion on round-trip.

### Section Heading Convention

`## section.prefix` splits at the **first** dot: section becomes the YAML top-level key, prefix becomes the nested key path. Example: `## spring.datasource` -> section=`spring`, prefix=`datasource`.

### Top-level Scalar Values

YAML top-level scalars (e.g., `debug: true`) are stored with key `__scalar__` and exported back as direct root-level values (not nested under `__scalar__`).

### IntelliJ Plugin File Watching

- Uses full normalized path comparison (forward slashes on all platforms)
- Handles `VFileContentChangeEvent`, `VFileCreateEvent`, `VFileMoveEvent`, `VFilePropertyChangeEvent` (rename)
- Debounces with `Alarm` API (500ms), triggers `generateAll()` to avoid missing concurrent changes
