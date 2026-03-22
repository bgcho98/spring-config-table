# Spring Config Table (SCT)

A tool for managing Spring Boot multi-environment YAML config files using Markdown tables.

View and edit all environment settings (dev, beta, real, gov, ...) in a single master Markdown file, and automatically generate per-environment `application-{profile}.yml` files.

## Key Features

- **Markdown → YAML Generation** — Auto-generate per-environment YAML from master Markdown tables (with comments)
- **YAML → Markdown Migration** — Batch-convert existing YAML files to a master Markdown (preserving comments)
- **YAML Lens** — View YAML or Markdown files as a searchable property table with double-click source navigation and CSV export
- **Visual Table Editor** — Master-Detail layout to browse/search properties by group and edit values + comments per environment
- **Auto-Detection** — Automatically regenerate YAML when master file changes (IntelliJ / Maven)
- **Multi-Module** — Configure different master files and output paths per module
- **Spring Metadata Integration** — Type detection, auto-completion, and unknown property warnings from `spring-configuration-metadata.json`

## Master Markdown Format

```markdown
## server

| env      | port                        | host                       |
|----------|-----------------------------|----------------------------|
| _default | 8080 <!-- HTTP server port --> | localhost <!-- bind addr --> |
| beta     | 9090                        |                            |
| real     | 80 <!-- prod port -->       | 0.0.0.0                    |

## spring.datasource

| env      | url                          | username |
|----------|------------------------------|----------|
| _default | jdbc:mysql://localhost/db     | root     |
| beta     | jdbc:mysql://beta-db/db      |          |
| real     | jdbc:mysql://real-db/db      | admin    |
```

- `_default` = `application.yml` (default profile)
- Empty cell = inherit from default
- `null` = explicit null override
- `"value"` = forced string (preserves values that look like boolean/number)
- `<!-- comment -->` = converts to YAML inline comment (per-environment comments supported)

## Modules

| Module | Description |
|--------|-------------|
| `sct-core` | Parser, writer, exporter, importer (core library) |
| `sct-maven-plugin` | Maven `generate-resources` phase integration |
| `sct-intellij-plugin` | IntelliJ plugin (editor, YAML Lens, migration, auto-generation) |

## Quick Start

### Requirements

- Java 21+
- Maven 3.9+

### Build

```bash
# Build Maven modules + install locally
mvn clean install

# Build IntelliJ plugin (requires sct-core in mavenLocal)
cd sct-intellij-plugin
./gradlew buildPlugin
```

Build output:
- IntelliJ plugin ZIP: `sct-intellij-plugin/build/distributions/sct-intellij-plugin-1.0.0.zip`

### Maven Plugin

```xml
<plugin>
    <groupId>com.pinkmandarin</groupId>
    <artifactId>sct-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <masterFile>${project.basedir}/master-config.md</masterFile>
    </configuration>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
        </execution>
    </executions>
</plugin>
```

Running `mvn compile` (or higher) auto-generates `master-config.md` → `src/main/resources/application*.yml`.

### IntelliJ Plugin

**Install:** Settings > Plugins > Install Plugin from Disk, select the ZIP.

Or install from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30833-spring-config-table).

#### Visual Table Editor

Open a `master-config.md` file and click the **Table** tab at the bottom of the editor. Or right-click → **Open Table Editor**.

- **Left panel**: Properties grouped by first key segment (datasource, rabbitmq, ...) + search filter
- **Right panel**: Edit values + comments per environment in a vertical form
- `●` indicator = property has environment overrides
- Spring metadata-based type detection (falls back to auto-detect from value)
- Add/Rename/Delete Property, Section, Environment

#### YAML Lens (Viewer)

1. Select YAML files, Markdown master files, or a directory in Project View
2. Right-click → **YAML Lens**
3. Real-time property/value/profile search, natural sort order (1, 2, 10 — not lexicographic)
4. Double-click → navigate to source file line
5. CSV export
6. Modeless — editor remains interactive

#### YAML → Markdown Migration

1. Select `application*.yml` files in Project View
2. Right-click → **Migrate YAML to Master Markdown**
3. Choose save location → master Markdown generated (with comments)

#### Auto YAML Generation

1. Configure mappings in **Settings > Tools > Spring Config Table** (master file path ↔ output directory)
2. YAML auto-generated on master file save (500ms debounce)
3. Manual: **Tools > Generate YAML from Master Markdown**

#### Environment Sort Order

Configure two sort orders in Settings:

- **Lifecycle order**: `default, local, dev, alpha, beta, beta-dr, real, release, dr`
- **Region order**: `gov, ncgn, ngcc, ngsc, ninc, ngovc, ngoic`

Result: base group → gov group → ncgn group → ..., with lifecycle order applied within each group.

## Escape Rules

| Character | Markdown Notation | Description |
|-----------|------------------|-------------|
| `\|` | `\|` | Pipe (prevents cell delimiter conflict) |
| `\n` | `\n` | Newline |
| `\\` | `\\` | Backslash |
| `\"` | `\"` | Quote (inside quoted strings) |
| `<!-- -->` | `<!-- comment -->` | YAML comment (per-environment) |

## Comment Round-Trip

```
Original YAML                    → Markdown                             → Generated YAML
port: 8080  # HTTP server port   → | 8080 <!-- HTTP server port --> |   → port: 8080 # HTTP server port
```

- YAML `# comment` → Markdown `<!-- comment -->` (during migration)
- Markdown `<!-- comment -->` → YAML `# comment` (during generation)
- Per-environment comments supported
- Comment matching: exact key match preferred; last-segment match only when unambiguous

## Known Constraints

- Section ordering: `server` → `spring` → `management` → `springdoc` → rest alphabetical
- First dot in section name becomes YAML top-level key split: `## com.example.config` → section=`com`, prefix=`example.config`
- `---` multi-document YAML files are merged on import
- Values with significant leading/trailing whitespace are automatically quoted
- YAML comment extraction is best-effort (inline `#` only, block comments not supported)
- Hyphen-prefixed values like `-Xmx512m` are handled correctly (separator detection guards against false positives)

## Tests

```bash
mvn -pl sct-core test
```

70 tests covering: round-trip (pipe, newline, backslash, quotes, null, boolean/numeric strings, whitespace, comments), multi-document YAML, top-level scalars, E2E pipeline, environment sorting.

## License

[MIT License](LICENSE) © 2026 PinkMandarin
