# LabBench Database Merger

Merges two LabBench databases (schema version 11.0) into a third target database with full deduplication support and foreign key integrity.

## Overview

The merger operates in four phases:

1. **Version Validation & Properties** — Validates both databases share the same schema version, then merges property values (summing counters, preserving shared identifiers).

2. **Cocktail Table Deduplication** — Compares `cyclesequencing_cocktail` and `pcr_cocktail` rows field-by-field. Duplicates are mapped to existing target IDs; unique rows get new auto-increment IDs. When non-identical rows share the same name across databases, the names are prefixed with the database name to avoid ambiguity.

3. **Thermocycle Hierarchy Deduplication** — Compares complete `thermocycle → cycle → state` hierarchies as a unit. Two thermocycles are duplicates only if name, all cycles (repeats, order), and all states (temp, length, order) match exactly. If notes differ between matched hierarchies, they are concatenated in the target.

4. **Sequential Table Copy with Offset** — Remaining tables are copied in foreign-key dependency order. DB1 rows keep original IDs. DB2 rows get offset IDs (`original_id + DB1_row_count`). Foreign keys are adjusted using either offsets (non-deduplicated references) or mappings (deduplicated references).

## Data Integrity Checks

### Plan Mode Checks

The plan task runs 12 validation steps before reporting whether the merge can proceed:

| # | Check | Severity |
|---|-------|----------|
| 1 | **Database permissions** — For MySQL databases, verifies SELECT on sources and SELECT/INSERT/CREATE on target via `SHOW GRANTS`. Skipped for SQLite. | Error |
| 2 | **Database version match** — Both databases must have the same schema version. | Error |
| 3 | **Full version match** — The `fullDatabaseVersion` property must match. | Error |
| 4 | **Record counts** — Counts rows in all tables in both databases. | Info |
| 5–6 | **Cocktail deduplication** — Simulates CS and PCR cocktail dedup, identifying full-row duplicates and names that will be prefixed. | Info |
| 7 | **Thermocycle deduplication** — Simulates thermocycle hierarchy dedup. | Info |
| 8 | **Extraction ID uniqueness** — `extraction.extractionId` must be unique across both databases. Shows plate name and well location for any conflicts. | Error |
| 9 | **Extraction barcode uniqueness** — Checks `extraction.extractionBarcode` for duplicates within each database and between them. Shows counts and affected plate names (per database) on console; writes full barcode/plate/well details to `duplicate_barcodes.txt`. | Warning |
| 10 | **Plate name uniqueness** — `plate.name` must be unique across both databases. | Error |
| 11 | **Workflow name conflicts** — Simulates the workflow renaming strategy and checks for conflicts. | Error |
| 12 | **Duplicate plate/location reactions** — Checks for multiple extraction, PCR, or cyclesequencing reactions pointing to the same plate and well location within each database. Lists every occurrence broken down by reaction type and database. During merge, only the most recent reaction (highest id) is kept. | Warning |

Errors block the merge. Warnings are reported but do not prevent merging.

### Check Summary

The plan report ends with a CHECK SUMMARY showing ✓, ✗, or ⚠ for every check. The text reflects the actual result — for example `"No duplicate plate.name values between DB1 and DB2"` when passing, or `"Duplicate plate.name values found between DB1 and DB2"` when failing.

### Plate/Location Deduplication

When multiple reactions of the same type (extraction, pcr, or cyclesequencing) share the same plate and well location within a single database, this typically indicates a re-run or correction. During merge, only the most recent reaction (highest `.id`) is copied to the target database. The plan output shows full details:

```
  DUPLICATE PLATE/LOCATION REACTIONS (will keep most recent only)
───────────────────────────────────────────────────────────────────────────
  extraction: 2 to skip (DB1: 1, DB2: 1)
    DB1 extraction: MyPlate / A5 — ids [12, 45], keeping id=45, skipping 1
    DB2 extraction: OtherPlate / B3 — ids [8, 15, 22], keeping id=22, skipping 2
```

The skip IDs are held in memory during merge (the affected set is expected to be small) and checked against each row during the streaming copy, avoiding needless re-queries.

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

## MySQL Compatibility

The schema creation step handles several MySQL-specific limitations automatically:

- **REFERENCES privilege** — If the target database user lacks the `REFERENCES` privilege, foreign key constraints are silently omitted from `CREATE TABLE` statements. A test query is run first to detect this. Tables are still created with the correct columns and indexes.
- **DATE defaults** — MySQL does not allow `CURRENT_TIMESTAMP` as a default for `DATE` columns. The merger uses `DATETIME DEFAULT CURRENT_TIMESTAMP` for MySQL and `DATE DEFAULT CURRENT_TIMESTAMP` for SQLite.
- **CREATE INDEX IF NOT EXISTS** — Not supported by MySQL. The merger uses plain `CREATE INDEX` and catches duplicate-index errors silently.
- **Skip schema creation** — If the target tables already exist (e.g. created by LabBench itself), schema creation can be skipped entirely with the `--skip-schema` flag.

## Configuration

### Properties File (recommended)

Edit `merger.properties` with your database URLs and credentials:

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

# Skip schema creation (set to true if target tables already exist):
# skip.schema=false

# Test mode: copy only 10 rows per table (tier 1 and above):
# test.mode=false
```

Each database can have its own username and password. If per-database credentials are not set, the shared `db.username` / `db.password` values are used as a fallback. This allows read-only accounts on the source databases and a write-capable account on the target.

### Database Permissions Required

| Database | Permissions Required |
|----------|---------------------|
| DB1 (source) | `SELECT` only |
| DB2 (source) | `SELECT` only |
| Target | `SELECT`, `INSERT`, `CREATE TABLE` |

`REFERENCES` is used if available (for foreign key constraints) but is not required — the merger auto-detects and omits FK clauses if the privilege is missing.

For MySQL, the plan task verifies these permissions via `SHOW GRANTS`. For SQLite, file-level access is sufficient.

## Build

```bash
cd db-merger
gradle build

# Build fat JAR for standalone distribution
gradle fatJar
```

## Usage

### Gradle

```bash
# Plan mode (uses merger.properties)
gradle plan

# Merge mode
gradle merge

# Specify a different properties file
gradle plan -Pconfig=/path/to/my-config.properties

# Pass database URLs directly
gradle plan \
  -Pdb1=jdbc:sqlite:/path/to/db1.db \
  -Pdb2=jdbc:sqlite:/path/to/db2.db \
  -Ptarget=jdbc:sqlite:/path/to/target.db

# Skip schema creation (target tables already exist)
gradle merge -PskipSchema

# Test mode (copy only 10 rows per tier 1+ table)
gradle merge -PtestMode

# Resume an interrupted merge
gradle merge -Presume

# Combine flags
gradle merge -PskipSchema -PtestMode
```

### Fat JAR (standalone)

```bash
# Uses merger.properties in current directory
java -jar build/libs/db-merger-1.0.0-all.jar plan
java -jar build/libs/db-merger-1.0.0-all.jar merge

# Specify properties file
java -jar build/libs/db-merger-1.0.0-all.jar plan /path/to/merger.properties

# Full CLI args (legacy — shared credentials only)
java -jar build/libs/db-merger-1.0.0-all.jar plan <db1-url> <db2-url> <target-url> [username] [password]

# With flags
java -jar build/libs/db-merger-1.0.0-all.jar merge --skip-schema
java -jar build/libs/db-merger-1.0.0-all.jar merge --test
java -jar build/libs/db-merger-1.0.0-all.jar merge --resume
java -jar build/libs/db-merger-1.0.0-all.jar merge --skip-schema --test
```

### Command-Line Flags

| Flag | Properties Equivalent | Description |
|------|----------------------|-------------|
| `--skip-schema` | `skip.schema=true` | Skip `CREATE TABLE` statements. Use when target tables already exist. |
| `--test` | `test.mode=true` | Copy only 10 rows per table for tier 1 and above. Setup and dedup tables are copied in full to preserve FK integrity. |
| `--resume` | `resume=true` | Resume a previously interrupted merge from where it left off. |
| `--server-side` | `server.side=true` | Use `INSERT INTO...SELECT` for DB1 tables when DB1 and target are on the same MySQL server. |
| `--server-side-db2` | `server.side.db2=true` | Use `INSERT INTO...SELECT` with offset arithmetic for eligible DB2 tables (same server required). |

## Server-Side Transfer

When DB1 and the target database are on the same MySQL server, `--server-side` tells the merger to use `INSERT INTO target.table SELECT ... FROM db1.table` for DB1 data. This keeps the data entirely within the MySQL server — no network transfer through the Java process — which is significantly faster for large tables.

```bash
gradle merge -PserverSide
# or both DB1 and DB2 server-side:
gradle merge -PserverSide -PserverSideDb2
# or just DB2:
gradle merge -PserverSideDb2
```

The merger automatically validates server-side mode at startup:
1. Both the source DB and target must be MySQL (not SQLite)
2. The host:port parsed from both JDBC URLs must match
3. The target connection must have SELECT access to the source database (tested with a probe query)

If any check fails, it falls back to standard mode with a warning.

**What uses `--server-side` (DB1):**
- All DB1 table copies that don't require row transformation (failure_reason, gelimages, pcr_thermocycle, cyclesequencing_thermocycle, plate, extraction, workflow, gel_quantification, assembly, pcr, cyclesequencing, traces, sequencing_result)
- Cocktail and thermocycle tables are copied server-side with a post-copy UPDATE for name prefixing
- For tables with plate/location dedup, `WHERE id NOT IN (...)` excludes the skip IDs server-side

**What uses `--server-side-db2` (DB2):**
- DB2 tables with simple offset-only transforms: gelimages, gel_quantification, assembly, traces, sequencing_result
- Uses `SELECT id + offset, ..., fk_col + fk_offset, ...` to apply offsets in SQL

**What always goes through Java:**
- DB2 tables needing map-based FK remapping: pcr, cyclesequencing (cocktail/thermocycle maps)
- DB2 workflow (name renumbering logic)
- DB2 extraction (plate/location dedup skips)
- Cocktail and thermocycle dedup (row-by-row comparison logic for DB2)
- Version/properties merge (trivial, just a few rows)

## Resumable Merges

If a merge is interrupted (e.g. by a network dropout or crash), it can be resumed from where it left off using `--resume`. The merger tracks progress in a `_merge_progress` table in the target database, recording each completed step.

```bash
# First attempt (interrupted at step 11)
java -jar db-merger.jar merge

# Resume — steps 1-10 are skipped, continues from step 11
java -jar db-merger.jar merge --resume
```

On resume, the merger:
1. Loads completed steps from `_merge_progress` in the target
2. Skips any step already recorded as complete
3. Rebuilds in-memory mappings (cocktail dedup maps, thermocycle maps, max-ID offsets) from the data already present in the target, so subsequent steps produce correct foreign key references
4. Each step commits independently — only the failed step is lost, not all prior work
5. For large tables (`extraction`, `workflow`, `pcr`, `cyclesequencing`, `gelimages`, and `traces`), progress is committed after every batch of 100 rows. On resume, the merger checks the max ID already in the target and uses `WHERE id > maxTargetId` to read only uncopied rows from the source, so at most one batch of work is lost
6. On successful completion, the `_merge_progress` table is dropped

Without `--resume`, a fresh merge starts from scratch (and if `--skip-schema` is used, existing target data is truncated first).

## Progress Logging

Plan mode logs progress as `[Plan  1/12]` through `[Plan 12/12]`.

Merge mode logs as `[Merge  1/18]` through `[Merge 18/18]` for each table. During streaming copy, a progress message is logged every 1,000 rows. For resumable tables (`extraction`, `workflow`, `pcr`, `cyclesequencing`, `gelimages`, `traces`), progress is committed after each batch of 100 rows, and a commit message is logged every 1,000 rows. In test mode, a banner is printed at startup indicating the row limit.

## Performance Notes

- **Streaming copy**: Large tables are read and inserted in batches of 100 rows (configurable via `BATCH_SIZE`), keeping memory usage constant regardless of table size.
- **Bulk thermocycle loading**: Thermocycle hierarchies are loaded in exactly 3 queries (all thermocycles, all cycles, all states) then grouped in Java, avoiding N+1 query overhead.
- **Plate/location skip sets**: Reactions to skip during merge are identified upfront and held in a `HashSet` for O(1) lookup during streaming, adding negligible overhead.

## Error Conditions

| Condition | Behavior |
|-----------|----------|
| Version mismatch | Abort with error message |
| `fullDatabaseVersion` mismatch | Abort with error message |
| Missing MySQL permissions (SELECT/INSERT/CREATE) | Abort with error message |
| Missing MySQL REFERENCES privilege | Schema created without FK constraints (warning logged) |
| Duplicate `extraction.extractionId` between DBs | Abort with error message |
| Duplicate `plate.name` between DBs | Abort with error message |
| Workflow name conflict after rename | Abort with error message |
| Duplicate `extraction.extractionBarcode` | Warning only — details written to `duplicate_barcodes.txt` |
| Duplicate plate/location reactions | Warning only — most recent kept during merge |
| SQL error during merge | Rollback all changes, abort |

## Logging

Set log level in `src/main/resources/logback.xml`. DEBUG level shows individual ID mappings; INFO shows phase-level progress.
