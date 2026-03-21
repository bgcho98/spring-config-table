# AGENTS.md

## Project Overview

Spring Config Table (SCT) - A tool that manages Spring Boot multi-environment YAML config files using Markdown tables. One master Markdown file contains all environment settings in a human-readable table format, and the tool generates per-environment `application-{profile}.yml` files. Also includes YAML Lens (table viewer) and YAML-to-Markdown migration.

## Architecture

```
sct-core/              Core library (parser, writer, exporter, importer)
sct-cli/               CLI tool (fat JAR)
sct-maven-plugin/      Maven plugin (generate-resources phase)
sct-intellij-plugin/   IntelliJ IDEA plugin (Gradle-based, separate from Maven)
```

### Data Flow

```
[Existing YAML files] --migrate--> [Master Markdown] --generate--> [Per-env YAML files]
                       \                                          /
                        `-- YAML Lens (read-only table viewer) --'
```

### Key Classes — sct-core

- `MasterMarkdownParser` — Parses Markdown tables into `ParseResult(List<Property>, List<Environment>)`
- `MasterMarkdownWriter` — Writes `List<Property>` to Markdown table format
- `YamlExporter` — Converts `List<Property>` to per-environment YAML files via SnakeYAML
- `YamlImporter` — Imports existing Spring Boot YAML files (supports multi-document `---`)
  - `parseFile(Path, envName)` — public, parses a single YAML file
  - `extractEnvName(Path)` — public static, extracts profile name from filename
  - `importDirectory(Path)` — parses all `application*.yml` in a directory
- `Property(section, key, env, value, valueType)` — Core data model (immutable record)
- `Environment(name)` — Environment identifier with `toDisplayName()` / `fromDisplayName()` conversion

### Key Classes — sct-intellij-plugin

- `SctGenerator` — Generates YAML from master Markdown (called by watcher/action)
- `SctStartupActivity` — VFS listener with Alarm debounce for auto-generation
- `SctFileWatcher` — Project-scoped service caching master file paths
- `SctSettings` — Persistent settings with `List<ModuleMapping>` (master file → output dir)
- `SctConfigurable` — Settings UI with editable table for multi-module mappings
- `YamlLensAction` — Right-click action: YAML files → searchable property table dialog
  - Property/Value/Profile filtering with `TableRowSorter`
  - CSV export with BOM UTF-8
  - Uses `DialogWrapper` (not JFrame)
- `MigrateToMarkdownAction` — Right-click action: YAML files → master Markdown
  - File save dialog, background thread, notification on completion
- `SctBundle` — i18n via `DynamicBundle` (EN + KO)

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
- No Lombok — use Java records for data classes
- SnakeYAML with `SafeConstructor` (security)
- IntelliJ plugin: `DynamicBundle` for i18n, `Alarm` for debounce, `@Service(Service.Level.PROJECT)` for project-scoped services
- Actions registered in `plugin.xml`, text/description via `resource-bundle` key convention

## Important Design Decisions

### Escape Protocol (Markdown table cells)

Writer escapes in this order: `\` → `\\`, `\n` → `\\n`, `|` → `\|`, then `"` → `\"` inside quotes.
Parser: pipe (`\|`) is handled during cell splitting in `parseTableRow()`.
Quote detection happens BEFORE `unescapeCellValue()`. Inside quotes, `\"` → `"`.
General unescape: `\n` → newline, `\\` → `\`.

### Type Preservation

`Property.valueType` preserves original types (`bool`, `int`, `float`, `string`, `null`).
Writer quotes string values that look like boolean/number/null to prevent type coercion on round-trip.
`needsQuoting()` also handles leading/trailing whitespace and empty strings.

### Section Heading Convention

`## section.prefix` splits at the **first** dot: section becomes the YAML top-level key, prefix becomes the nested key path. Example: `## spring.datasource` → section=`spring`, prefix=`datasource`.

### Top-level Scalar Values

YAML top-level scalars (e.g., `debug: true`) are stored with key `__scalar__` and exported back as direct root-level values (not nested under `__scalar__`).

### YAML File Matching

`application(-[^.]+)?.ya?ml` pattern — matches `application.yml`, `application-beta.yml`, but NOT `applicationFOO.yml`.

### IntelliJ Plugin File Watching

- Uses full normalized path comparison (forward slashes on all platforms for VirtualFile compatibility)
- Handles `VFileContentChangeEvent`, `VFileCreateEvent`, `VFileMoveEvent`, `VFilePropertyChangeEvent` (rename)
- Debounces with `Alarm` API (500ms), triggers `generateAll()` to avoid missing concurrent changes

### IntelliJ Plugin Actions

All registered in `plugin.xml` `<actions>`:
- `GenerateYamlAction` → Tools menu (manual generate from settings mappings)
- `YamlLensAction` → Project View popup (select YAML files → table dialog)
- `MigrateToMarkdownAction` → Project View popup (select YAML files → save as Markdown)
