# AGENTS.md

## Project Overview

Spring Config Table (SCT) — Manages Spring Boot multi-environment YAML config files using Markdown tables. One master Markdown file contains all environment settings in a human-readable table format with comments. The tool generates per-environment `application-{profile}.yml` files with inline comments preserved.

## Architecture

```
sct-core/              Core library (parser, writer, exporter, importer)
sct-maven-plugin/      Maven plugin (generate-resources phase)
sct-intellij-plugin/   IntelliJ IDEA plugin (Gradle-based, separate from Maven)
```

### Data Flow

```
[YAML files] --migrate--> [Master Markdown] --generate--> [Per-env YAML files]
  # comment                  <!-- comment -->                 # comment

YAML Lens: read-only table viewer for both YAML and Markdown files
Table Editor: Master-Detail WYSIWYG editor for Markdown files
```

### Key Classes — sct-core

- `MasterMarkdownParser` — Parses Markdown tables + `<!-- comments -->` into `ParseResult`
- `MasterMarkdownWriter` — Writes `List<Property>` to aligned Markdown tables with `<!-- comments -->`
  - Column-width-aligned output (no reformat warnings)
  - Configurable environment sort order (lifecycle × region)
  - Priority section ordering (server, spring, management, springdoc)
- `YamlExporter` — Converts properties to per-env YAML files with `# comments`
  - `insertComments()`: tracks YAML indentation to build full key path for comment matching
- `YamlImporter` — Imports YAML files (multi-document `---`) with comment extraction
  - `attachComments()`: 4-strategy matching (exact, key, last segment, suffix)
  - `importFiles(List<Path>)` — used by both CLI and IntelliJ migrate action
- `Property(section, key, env, value, valueType, comment)` — Core immutable record
  - `of()` — from Java objects (type from instanceof)
  - `ofParsed()` — from user input strings (type auto-detected: "false"→bool, "42"→int)
  - `withComment()` — returns copy with updated comment
- `Environment(name)` — With 2D sort comparator (lifecycle × region)
  - `comparator(lifecycleOrder, regionOrder)` — configurable sort
- `ParseResult(properties, environments)` — Shared result type

### Key Classes — sct-intellij-plugin

- `SctGenerator` — Generates YAML from master Markdown
- `SctStartupActivity` — VFS listener with Alarm debounce, pending path accumulation
- `SctFileWatcher` — Project-scoped service, normalized path cache
- `SctSettings` — Persistent settings: mappings, autoGenerate, lifecycleOrder, regionOrder
- `SctConfigurable` — Settings UI: mappings table + lifecycle/region order fields
- `YamlLensAction` — Modeless dialog: YAML/MD → searchable property table
- `MigrateToMarkdownAction` — YAML files → master Markdown with comments
- `OpenTableEditorAction` — Opens Table editor tab or fallback dialog
- `YamlFileCollector` — Collects YAML/MD files from action events
- `NaturalOrderComparator` — "item2" < "item10" sorting

#### Editor (`editor/` package)

- `SctEditorProvider` — FileEditorProvider for `*config*.md` / `*master*.md` files
- `SctSimpleTableEditor` — Master-Detail editor (primary)
  - Left: grouped property list with search filter
    - Groups by first dot-segment when >10 keys (matches Writer's GROUP_THRESHOLD)
    - Non-selectable group headers with separator lines
    - `●` marks properties with environment overrides
  - Right: vertical form with value + comment fields per environment
    - Type detection: Spring metadata first → `Property.ofParsed()` fallback
    - Comment field: italic gray, inline with value field
  - Toolbar: Section selector, Add/Rename/Delete, Save/Reload, +/- Env
- `SctTableEditorDialog` — Modeless dialog wrapper for editor
- `SpringMetadataService` — Scans classpath for `spring-configuration-metadata.json`
  - Uses Gson (bundled in IntelliJ) for JSON parsing
  - Property suggestions for column add
  - Type coercion (java.lang.Boolean → "bool", etc.)
  - Unknown property warnings on column headers

## Build

```bash
mvn clean install
cd sct-intellij-plugin && ./gradlew buildPlugin
```

## Test

```bash
mvn -pl sct-core test    # 70 tests
```

## Coding Conventions

- Java 21: `var`, records, sealed interfaces, switch expressions, pattern matching
- Package: `com.pinkmandarin.sct.*`
- No Lombok — Java records for data classes
- SnakeYAML with `SafeConstructor` (security)
- IntelliJ plugin: `DynamicBundle` i18n (EN+KO), `Alarm` debounce, `@Service(PROJECT)`
- Actions in `plugin.xml` with `resource-bundle` key convention

## Important Design Decisions

### Comment Round-Trip

```
YAML: port: 8080  # HTTP port
  ↓ YamlImporter.attachComments() — 3-strategy key matching
    1. Exact match (full key or section.key)
    2. Exact match on property key
    3. Last segment match (only if unambiguous — 1 occurrence)
Markdown: | 8080 <!-- HTTP port --> |
  ↓ MasterMarkdownParser — extracts <!-- --> from cells
Property(value="8080", comment="HTTP port")
  ↓ YamlExporter.insertComments() — indentation-based path tracking
YAML: port: 8080 # HTTP port
```

### Type Detection (Editor)

1. Spring metadata: `spring-configuration-metadata.json` → `java.lang.Boolean` → `"bool"`
2. Fallback: `Property.ofParsed("false")` → `"bool"`, `"42"` → `"int"`

### Escape Protocol

Writer: `\` → `\\`, `\n` → `\\n`, `|` → `\|`, `"` → `\"` (inside quotes only).
Parser: pipe in `parseTableRow()`, quotes before `unescapeCellValue()`, then `\n`→newline, `\\`→`\`.

### Environment Sorting (2D)

Two configurable lists: lifecycle order + region order.
Sort key: `(regionIdx, lifecycleIdx, alphabetical)`.
Parsing: prefix match (`gov-beta`), suffix match (`beta-gov`), exact region (`gov`).

### Section Ordering

`PRIORITY_SECTIONS`: server, spring, management, springdoc first. Rest alphabetical.

### Grouped Property List

When section has >10 keys, groups by first dot-segment with visual separators.
Display key has group prefix stripped. Matches `MasterMarkdownWriter.GROUP_THRESHOLD`.

### Table Output Alignment

Writer pads all columns to max cell width per column. Separator uses matching dashes.
Prevents IntelliJ Markdown "reformat table" warnings.

### Separator Detection

Parser checks ALL cells in a row match `-[- ]*` before treating as separator.
Prevents data like `-Xmx512m` from being misidentified as separator line.

### Thread Safety

- `SctStartupActivity`: pending paths drained via `iterator.remove()` (atomic per-key)
- `SctFileWatcher`: volatile `Set<String>` replaced atomically, wrapped in `Collections.unmodifiableSet()`
- `SctSettings.getMappings()`: returns `List.copyOf()` (defensive copy)
