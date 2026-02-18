# LabBench Database Merger

Merges two LabBench databases (schema version 11.0) into a third target database with full deduplication support and foreign key integrity.

## Overview

The merger operates in four phases:

1. **Version Validation & Properties** — Validates both databases share the same schema version, then merges property values (summing counters, preserving shared identifiers).

2. **Cocktail Table Deduplication** — Compares `cyclesequencing_cocktail` and `pcr_cocktail` rows field-by-field. Duplicates are mapped to existing target IDs; unique rows get new auto-increment IDs.

3. **Thermocycle Hierarchy Deduplication** — Compares complete `thermocycle → cycle → state` hierarchies as a unit. Two thermocycles are duplicates only if name, all cycles (repeats, order), and all states (temp, length, order) match exactly. If notes differ between matched hierarchies, they are concatenated in the target.

4. **Sequential Table Copy with Offset** — Remaining tables are copied in foreign-key dependency order. DB1 rows keep original IDs. DB2 rows get offset IDs (`original_id + DB1_row_count`). Foreign keys are adjusted using either offsets (non-deduplicated references) or mappings (deduplicated references).

## Table Copy Order

| Tier | Tables | Dependencies |
|------|--------|-------------|
| Setup | `databaseversion`, `properties` | None |
| Dedup | `cyclesequencing_cocktail`, `pcr_cocktail` | None |
| Dedup | `thermocycle`, `cycle`, `state` | cycle→thermocycle, state→cycle |
| Tier 1 | `failure_reason`, `gelimages`, `pcr_thermocycle`, `cyclesequencing_thermocycle` | None / plate |
| Tier 2 | `plate`, `extraction`, `workflow` | plate→thermocycle, extraction→plate, workflow→extraction |
| Tier 3 | `gel_quantification`, `assembly`, `pcr`, `cyclesequencing` | Multiple FKs |
| Tier 4 | `traces`, `sequencing_result` | cyclesequencing, assembly |

## Foreign Key Mapping

For DB2 records, foreign keys are resolved as follows:

| FK Column | Strategy | Source |
|-----------|----------|--------|
| `thermocycle` | Mapping lookup | `thermocycleMap` |
| `cycle` (in pcr/cs_thermocycle) | Mapping lookup | `thermocycleMap` |
| `cocktail` (in pcr) | Mapping lookup | `pcrCocktailMap` |
| `cocktail` (in cyclesequencing) | Mapping lookup | `cscocktailMap` |
| `plate` | Offset | `db1Count["plate"]` |
| `extraction` / `extractionId` | Offset | `db1Count["extraction"]` |
| `workflow` | Offset | `db1Count["workflow"]` |
| `failure_reason` | Offset | `db1Count["failure_reason"]` |
| `reaction` (in traces) | Offset | `db1Count["cyclesequencing"]` |
| `assembly` (in seq_result) | Offset | `db1Count["assembly"]` |

## Workflow Name Handling

Workflow names follow the pattern `LOCUS_workflowXX`. To avoid duplicates, DB2 workflow numbers are offset by the maximum number found in DB1 for the same locus. If conflicts remain after offsetting, the merge aborts.

## Build

```bash
cd db-merger
gradle build

# Build fat JAR for standalone distribution
gradle fatJar
```

## Usage

### Properties File (recommended)

Edit `merger.properties` with your database URLs and credentials, then:

```bash
# Plan mode
gradle plan

# Merge mode
gradle merge

# Or specify a different properties file
gradle plan -Pconfig=/path/to/my-config.properties
```

Example `merger.properties`:
```properties
db1.url=jdbc:sqlite:/path/to/database1.db
db1.username=
db1.password=

db2.url=jdbc:sqlite:/path/to/database2.db
db2.username=
db2.password=

target.url=jdbc:sqlite:/path/to/merged.db
target.username=
target.password=

# Shared credentials (fallback when per-database not set):
# db.username=shareduser
# db.password=sharedpass
```

### Gradle with CLI Properties

```bash
gradle plan \
  -Pdb1=jdbc:sqlite:/path/to/db1.db \
  -Pdb2=jdbc:sqlite:/path/to/db2.db \
  -Ptarget=jdbc:sqlite:/path/to/target.db

gradle merge \
  -Pdb1=jdbc:mysql://localhost:3306/labbench1 \
  -Pdb2=jdbc:mysql://localhost:3306/labbench2 \
  -Ptarget=jdbc:mysql://localhost:3306/labbench_merged \
  -PdbUser=myuser \
  -PdbPassword=mypass
```

### Fat JAR (standalone)

```bash
# Uses merger.properties in current directory
java -jar build/libs/db-merger-1.0.0-all.jar plan
java -jar build/libs/db-merger-1.0.0-all.jar merge

# Specify properties file
java -jar build/libs/db-merger-1.0.0-all.jar plan /path/to/merger.properties

# Full CLI args (legacy)
java -jar build/libs/db-merger-1.0.0-all.jar plan <db1-url> <db2-url> <target-url> [username] [password]
```

## Example Plan Output

```
═══════════════════════════════════════════════════════════════════════════
  LABBENCH DATABASE MERGE PLAN
═══════════════════════════════════════════════════════════════════════════

  VERSION VALIDATION
───────────────────────────────────────────────────────────────────────────
  DB1 version:          11
  DB2 version:          11
  Versions match:       YES ✓

  DEDUPLICATION ANALYSIS
───────────────────────────────────────────────────────────────────────────
  Table                              DB1      DB2    Dupes   Target
  cyclesequencing_cocktail             5        4        2        7
  pcr_cocktail                         3        3        1        5
  thermocycle                          2        2        1        3

  ALL TABLE RECORD COUNTS
───────────────────────────────────────────────────────────────────────────
  Table                              DB1      DB2   Target
  ...

═══════════════════════════════════════════════════════════════════════════
  RESULT: Merge CAN proceed ✓
═══════════════════════════════════════════════════════════════════════════
```

## Error Conditions

| Condition | Behavior |
|-----------|----------|
| Version mismatch | Abort with error message |
| `fullDatabaseVersion` mismatch | Abort with error message |
| Duplicate `extraction.extractionId` between DBs | Abort with error message |
| Duplicate `plate.name` between DBs | Abort with error message |
| Workflow name conflict after rename | Abort with error message |
| SQL error during merge | Rollback all changes, abort |

## Logging

Set log level in `src/main/resources/logback.xml`. DEBUG level shows individual ID mappings; INFO shows phase-level progress.
