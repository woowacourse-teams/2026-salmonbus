# S3 history to RDS field mapping

## Authority and scope

The target contract is dev commit `d856d10819bf1d018ad43fa63714cc348f1fc643`.
The authoritative audit inputs are `audit-summary.json`, `field-mapping.json`,
`acceptance-fixture.json`, `trainer-read-contract.json`, `cutover-readiness.json`,
`source-freeze-confirmation-2.json`, `target-dev-delta.json`, and `postgres-sizing.json` under
`audit/s3-rds-migration/` in the audit worktree. `AUDIT.md` and the main body of the route mapping
report retain older counts and an obsolete adjacent-version proposal; they are not execution
authority.

This tool migrates only verified normalized observation history. It does not copy a raw response,
source object location, plate value, source pseudonym/HMAC, credential, HTTP header, result message,
quota invocation detail, or deployment/derived artifact.

| final authority measure | total | 3330 | 1650 |
|---|---:|---:|---:|
| importable `observation_batch` | 149,193 | 74,304 | 74,889 |
| importable `vehicle_observation` | 2,461,308 | 1,175,694 | 1,285,614 |
| overlap records retained in S3 only | 811 | 689 | 122 |
| overlap observations retained in S3 only | 14,924 | 14,364 | 560 |

The importable total is immutable base 142,129 batches / 2,324,399 observations plus terminal
catch-up 7,064 / 136,909. Whole-record station-roster quarantine is 28 batches / 569 observations.
The terminal overlap range is `2026-09-02T10:28:00.302Z` through
`2026-09-02T13:20:01.561Z`. The 190 HTTP 429 records are evidence of the shared upstream quota
incident and are not imported past the target authority boundary.

## Batch mapping

| verified source meaning | archive field | RDS target | rule |
|---|---|---|---|
| source account | `source_account` | `migration_source_record.source_account` | fixed personal source account; never inferred from target credentials |
| collector schema | `source_schema_version` | `migration_source_record.source_schema_version` | exact allowlisted `1.0.0` |
| record UUID | `source_record_id` | provenance primary key | scope by source account |
| semantic call identity | `semantic_batch_digest` | provenance unique key and batch digest | versioned length-prefixed SHA-256 tuple; contains no vehicle or plate value |
| import attempt identity | `attempt_key` | `observation_batch.attempt_key` | `s3v1:` namespace followed by the semantic digest |
| source route | `route_name`, `source_route_id` | current `route_version_id` | resolve only through the exact current route gate below |
| scheduled/request/response time | corresponding instant fields | batch timestamp columns | `response_received_at` is observation-time authority |
| HTTP/result | nullable integer fields | `http_status`, `result_code` | copy only scalar codes supported by the current schema |
| source classification | `outcome`, `failure_code` | same batch columns | map to current enums; failure batches remain timeline evidence |
| provider/stored/excluded counts | same names | same batch columns | require `provider_rows = stored_rows + excluded_rows` |
| normalization | `normalization_version` | same batch column | fixed `normalization-v1.0.0-s3-backfill` |
| collection strategy | `collection_strategy_version` | same batch column | preserve safe version string |
| historical origin | `S3_BACKFILL` | `observation_batch.ingestion_origin` | never manufacture `forecast_completed_at` |

`completed_at` is set to the verified source response time because the immutable source record is a
completed collection attempt. `forecast_completed_at` stays null. `seat_forecast`,
`stop_demand_statistics`, and `model_deployment` are unchanged by observation import.

## Observation mapping

| verified source meaning | RDS target | rule |
|---|---|---|
| source row position | `source_row_number` | preserve zero-based order |
| private vehicle identity | `vehicle_id` | exact scalar-to-text copy; non-null and non-blank for every imported row |
| plate identity | `plate_number` | always null; original remains only in source S3 |
| trip identity | `vehicle_trip_key` | null; no source mapping exists |
| station sequence/identity | `stop_order`, `stop_id` | exact current roster match or quarantine the whole record |
| running state | `running_state` | only 0, 1, or 2 |
| passed position | `passed_stop_order` | `stop_order - 1` for arriving state 1, otherwise `stop_order` |
| reported seats | `remaining_seats`, `seat_unknown_reason` | non-negative value or the versioned `REPORTED_UNKNOWN`/`NOT_REPORTED` reason |
| crowded | `crowd_level` | preserve 1..4; fold zero/out-of-range/missing to null |
| low-floor/type/tagless scalars | `vehicle_type`, `route_type`, `tagless` | nullable integral copy |

Private vehicle identity may exist only in a mode-0600 archive/staging file under a mode-0700
directory, encrypted transport, and the private RDS column. It must never appear in a manifest,
receipt, fixture, documentation example, log, or stdout. Source HMAC values are used only for
in-memory consistency checks and are not persisted by the archive.

## Route authority and continuity

No `route`, `route_version`, or `route_stop` row is inserted.

| route | current version | target authority, inclusive | accepted source last response | operation |
|---|---:|---|---|---|
| 3330 | 1 | `2026-09-02T10:27:51.330754Z` | `2026-09-02T10:27:45.315Z` | extend current `valid_from` to the accepted source minimum |
| 1650 | 2 | `2026-09-02T12:49:33.041299Z` | `2026-09-02T12:49:31.467Z` | extend current `valid_from` to the accepted source minimum |

The exact precondition covers one route, one current version, open `valid_to`, content digest,
turn sequence, ordered stops, boarding policy, departure fields where available, and the original
`valid_from`. The update changes only `valid_from`. The old value and both roster digests are stored
in `historical_import_route_binding` before observation merge. A mismatch rolls back the transaction.

The known 3330 source-last to first-live gap is 7.07582 seconds. The 1650 source-last to version-open
gap is 1.574299 seconds; its exact first live observation remains a read-only target preflight result.
The older global 5.87682-second measurement is immutable evidence but not import authority.

## Provenance and idempotency

The tool uses two independent identities:

- `(source_account, source_record_id)` proves source provenance.
- `(source_account, semantic_batch_digest)` detects semantic replay under a different object path.

`migration_source_record` binds both identities to the normalized-record digest, archive manifest,
import ledger, importer version, and final `observation_batch`. A committed shard can be resumed;
re-import of an identical prior record is a no-op, while either identity with changed content fails
the transaction.

## Terminal dataset identity

The one-time terminal archive is accepted only when all four confirmed digests are present in its
`terminalFreeze` object and the previous COMPLETE import is the exact immutable base.

| role | SHA-256 |
|---|---|
| immutable base inventory | `db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9` |
| 2026-09-02 terminal partition inventory | `f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e` |
| full record/raw history; dataset seal authority | `ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8` |
| data-analysis source closure composite | `75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7` |

The full-history digest must equal the terminal `SourceInventory.inventorySha256`. The selected delta
digest must equal the terminal-partition digest, and `previousInventorySha256` must equal the base
digest. RDS creates `historical_import_dataset_seal` only after reconciliation marks that terminal
import COMPLETE. Rolling the terminal import back removes the seal in the same transaction.

## Additive schema surface

Application Flyway V1..V12 is not edited. The tool uses a separate
`historical_import_schema_history` and adds:

- import batch, route boundary/binding, shard, record, and staging ledgers;
- `migration_source_record` provenance;
- `observation_batch.ingestion_origin` plus import/digest columns and constraints;
- the partial LIVE pending-forecast index;
- terminal dataset seal;
- forecast cutover control and advisory-lock fence;
- temporary-release and exact statistics-generation exclusion ledgers;
- `training_eligible_seat_forecast` and `training_eligible_stop_demand_statistics` views.

The worker's schema-compatibility probe uses a positive-only cache: it keeps checking while the
column is absent and permanently selects the LIVE-only query after the first successful detection.

The implementation's private archive record/manifest v2 is the executable format. The audit JSON
schemas remain mapping evidence; they are not passed directly to the importer. The executable v2
adds canonical schema identity, route rosters, normalized-record digests, terminal closure fields,
and strict 0600/0700 checks while preserving the audit field semantics above.
