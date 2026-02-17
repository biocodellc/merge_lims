# Table Creation and Copy Order

## Overview

Tables must be created and populated in an order that respects foreign key constraints. The 20 tables are organized into tiers based on their dependencies.

## Complete Order

### Setup Phase (Tables 1-2)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 1 | `databaseversion` | None | Single row, version must match |
| 2 | `properties` | None | Key-value pairs, some summed |

### Deduplication Phase (Tables 3-7)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 3 | `cyclesequencing_cocktail` | None | Field-by-field dedup |
| 4 | `pcr_cocktail` | None | Field-by-field dedup |
| 5 | `thermocycle` | None | Hierarchy dedup (parent) |
| 6 | `cycle` | `thermocycle` | Hierarchy dedup (child) |
| 7 | `state` | `cycle` | Hierarchy dedup (grandchild) |

### Tier 1 — No Complex Dependencies (Tables 8-11)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 8 | `failure_reason` | None | Simple offset |
| 9 | `gelimages` | `plate` | Plate FK uses offset |
| 10 | `pcr_thermocycle` | `thermocycle` (via cycle) | cycle FK uses thermocycle mapping |
| 11 | `cyclesequencing_thermocycle` | `thermocycle` (via cycle) | cycle FK uses thermocycle mapping |

### Tier 2 — Plate/Extraction Chain (Tables 12-14)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 12 | `plate` | `thermocycle` | thermocycle FK uses mapping |
| 13 | `extraction` | `plate` | plate FK uses offset |
| 14 | `workflow` | `extraction` | extractionId FK uses offset; names renamed |

### Tier 3 — Complex Dependencies (Tables 15-18)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 15 | `gel_quantification` | `extraction`, `plate` | Both FKs use offsets |
| 16 | `assembly` | `workflow`, `failure_reason` | Both FKs use offsets |
| 17 | `pcr` | `plate`, `workflow`, `pcr_cocktail`, `thermocycle` | Mixed offset + mapping |
| 18 | `cyclesequencing` | `plate`, `workflow`, `cyclesequencing_cocktail`, `thermocycle` | Mixed offset + mapping |

### Tier 4 — Deepest Dependencies (Tables 19-20)
| # | Table | Dependencies | Notes |
|---|-------|-------------|-------|
| 19 | `traces` | `cyclesequencing` | reaction FK uses cs offset |
| 20 | `sequencing_result` | `cyclesequencing`, `assembly` | Both FKs use offsets |

## Dependency Chain Diagrams

```
thermocycle ──┬──> cycle ──> state
              ├──> plate ──> extraction ──> workflow ──┬──> assembly ──> sequencing_result
              ├──> pcr                                 ├──> pcr
              └──> cyclesequencing ────────────────────┤
                                                       └──> cyclesequencing ──> traces
                                                                              └──> sequencing_result

cyclesequencing_cocktail ──> cyclesequencing
pcr_cocktail ──> pcr
failure_reason ──> assembly
```

## FK Resolution Summary

| Reference Type | Tables Using It | Resolution Method |
|---------------|----------------|-------------------|
| Thermocycle mapping | plate, pcr, cyclesequencing, pcr_thermocycle, cs_thermocycle | `thermocycleMap.get(db2_id)` |
| CS cocktail mapping | cyclesequencing | `cscocktailMap.get(db2_id)` |
| PCR cocktail mapping | pcr | `pcrCocktailMap.get(db2_id)` |
| Plate offset | extraction, pcr, cyclesequencing, gelimages, gel_quantification | `id + db1Count["plate"]` |
| Extraction offset | workflow, gel_quantification | `id + db1Count["extraction"]` |
| Workflow offset | assembly, pcr, cyclesequencing | `id + db1Count["workflow"]` |
| Failure reason offset | assembly | `id + db1Count["failure_reason"]` |
| Cyclesequencing offset | traces, sequencing_result | `id + db1Count["cyclesequencing"]` |
| Assembly offset | sequencing_result | `id + db1Count["assembly"]` |

## Important Notes

1. **Thermocycle hierarchy is an atomic unit** — all three tables (thermocycle, cycle, state) must be processed together during deduplication.
2. **Order within hierarchy matters** — cycles are compared in ID order within their thermocycle; states in ID order within their cycle.
3. **pcr_thermocycle and cyclesequencing_thermocycle** reference thermocycle IDs via their `cycle` column, which needs the thermocycle mapping applied.
4. **gelimages** must be created after plate because it has a plate FK, but it's listed in Tier 1 because it has no other complex dependencies.
5. **sequencing_result** has a composite primary key (reaction, assembly) — no auto-increment ID.
