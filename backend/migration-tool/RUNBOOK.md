# S3 to RDS migration runbook

## Status and hard boundary

This runbook is implementation-ready but has not been executed against personal S3, academy EC2, or
academy RDS. No command in an academy section may run without its named approval. Archive transfer,
database schema write, import, rollback, model promotion, cleanup, file deletion, security-group
change, and deployment are separate approval events.

Academy SSH is currently expected to be unavailable outside the academy IP allowlist. Do not probe
or retry it from this worktree. Finish local verification, report the required access, and wait.

## Build and local rehearsal

From `backend/`:

```bash
./gradlew :migration-tool:test --no-daemon --console=plain
./gradlew :worker-app:test --no-daemon --console=plain
./gradlew clean build --no-daemon --console=plain
```

The bootable artifact is currently
`migration-tool/build/libs/migration-tool-0.0.1-SNAPSHOT.jar`. Use a separately verified artifact
digest in an execution record; do not infer it from a filename.

`scripts/rehearse-local.sh` requires local Docker and zstd and reruns the migration-tool
Testcontainers suite. The full build also runs existing api/common/worker Testcontainers tests, so
the new module does not introduce the repository's first Docker requirement. `buildspec.yml` already
states that the CodeBuild project must have privileged mode.

## Fixed authority

Before generating any private archive, independently recheck these aggregate authorities:

| authority | value |
|---|---|
| target dev | `d856d10819bf1d018ad43fa63714cc348f1fc643` |
| 3330 target authority | `2026-09-02T10:27:51.330754Z` |
| 1650 target authority | `2026-09-02T12:49:33.041299Z` |
| immutable base inventory | `db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9` |
| terminal partition inventory | `f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e` |
| full-history seal authority | `ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8` |
| source closure composite | `75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7` |

The executable gate hard-pins these four source digests. A difference is a stop condition, not a new
delta to accept implicitly.

## Access required later

| access | purpose | current alternative |
|---|---|---|
| personal read-only AWS credentials | approved S3 List/Get inventory and archive build | none; do not copy a long-lived key to academy |
| academy EC2 SSH with pinned host key | resumable archive transfer and EC2-local CLI | wait for approved access restoration |
| EC2-local RDS credentials from `/etc/salmonbus/worker.env` | read-only preflight and separately approved writes | an already-approved SSM session can replace SSH; public HTTP can prove health only, not DB invariants |
| security-group source update | allow a new trusted operator IP | separate academy infrastructure approval; never perform as part of migration CLI |

Making RDS public or using personal credentials to reach it is not an alternative. Public API health
or board responses cannot replace route, storage, schema, count, or seal preflight.

## Private file rules

Use `umask 077`. Every inventory, receipt, archive, staging directory, and partial transfer has mode
0700 for directories and 0600 for files. Approval receipts may be 0600; a read-only SSH private key
may be 0400. Do not put actual host, user, identity path, URL, secret, vehicle value, plate value,
HMAC, or source object location in repository files or shell history.

The archive output directory must not exist before `archive-build`. The verifier rejects symlinks,
unexpected permissions, digest mismatch, extra/missing shard data, and noncanonical JSON.

## Phase 1: SOURCE_EXPORT approval

An operator creates a canonical mode-0600 receipt from `config/approval.json.example` with action
`SOURCE_EXPORT`, null artifact/database digests, and a short expiry. Issuing the receipt is approval;
the worker does not create it for itself.

Prepare two non-repository config copies from `config/source-export.properties.example`.

| config | date range | expected accepted | quarantine | overlap |
|---|---|---:|---:|---:|
| BASE | KST 2026-08-14..2026-09-01 | 142,129 / 2,324,399 | 27 / 545 | 0 / 0 |
| TERMINAL_DELTA | KST 2026-08-14..2026-09-02, with previous inventory | 7,064 / 136,909 | 1 / 24 | 811 / 14,924 |

Each pair is batches / observations. Both configs use the route authorities above. The terminal
config points to the verified base inventory, base archive manifest SHA, and a private copy of
`config/terminal-freeze-receipt.json.example`. Fill `drainedAt` with a post-drain time no later than
the inventory cutoff and not earlier than the latest selected object. Fill only the approved
schedule-identity digest; the four source closure digests are already pinned.

The source closure is not an audit artifact. Its authority file is
`/Users/idonghun/paseo/workspaces/2026-salmonbus/data-analysis/handoff/coefficient-bundle-v4-1/processed/final-source-closure.json`.
The tool does not read this file directly: `TerminalFreezeReceipt` reads `sourceClosureSha256` from
the private terminal receipt and `ArchiveManifest.TerminalFreeze.EXPECTED_SOURCE_CLOSURE_SHA256`
compares it with the hard pin. Before terminal receipt/archive build, run:

```bash
jq -e --arg expected '75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7' \
  '.schemaVersion == "v4-1-final-source-closure-v1" and .sourceClosureSha256 == $expected' \
  /Users/idonghun/paseo/workspaces/2026-salmonbus/data-analysis/handoff/coefficient-bundle-v4-1/processed/final-source-closure.json
```

With a local variable pointing to the verified boot jar:

```bash
java -jar "$MIGRATION_JAR" inventory \
  --config "$BASE_CONFIG" \
  --approval "$SOURCE_EXPORT_APPROVAL" \
  --output "$BASE_INVENTORY"

java -jar "$MIGRATION_JAR" archive-build \
  --config "$BASE_CONFIG" \
  --approval "$SOURCE_EXPORT_APPROVAL" \
  --inventory "$BASE_INVENTORY"

java -jar "$MIGRATION_JAR" inventory \
  --config "$TERMINAL_CONFIG" \
  --approval "$SOURCE_EXPORT_APPROVAL" \
  --output "$TERMINAL_INVENTORY"

java -jar "$MIGRATION_JAR" archive-build \
  --config "$TERMINAL_CONFIG" \
  --approval "$SOURCE_EXPORT_APPROVAL" \
  --inventory "$TERMINAL_INVENTORY"
```

Do not paste command output containing a private path into a ticket. The CLI emits aggregate counts
and digests only. Require `complete=true` for both archives and re-run local verification:

```bash
java -jar "$MIGRATION_JAR" archive-verify --archive "$BASE_ARCHIVE"
java -jar "$MIGRATION_JAR" archive-verify --archive "$TERMINAL_ARCHIVE"
```

The terminal source inventory must prove previous=base, selected=terminal partition, and
full=full-history authority. The terminal archive must name the base archive manifest as its
previous manifest.

## Phase 2: RSYNC_ARCHIVE_TRANSFER approval

Do not execute this phase while SSH access is unavailable. After access is restored, create a new
mode-0600 canonical receipt from `config/transfer-approval.json.example`, bound to one archive
manifest SHA and short expiry. Transfer base and terminal archives under separate approvals or
separately identified receipts.

Set the environment references required by `scripts/transfer-archive.sh`. The script validates the
complete manifest file set, every local shard hash, modes, pinned known_hosts file, approval identity,
safe remote root, and uses BatchMode with a 10-second connect timeout. A failed first SSH connection
stops the run; do not loop retries.

```bash
backend/migration-tool/scripts/transfer-archive.sh
```

The script leaves data under an `.incoming` path. It does not rename it final, import it, or delete
either copy. On EC2, verify archive hash/schema with the same jar before any final rename. Presigned
HTTPS is fallback only and requires a separately approved short-lived download plan; URLs are bearer
credentials and must enter only via private stdin.

## Phase 3: academy read-only preflight

Create an academy config copy with `target.kind=ACADEMY`,
`database.env-file=/etc/salmonbus/worker.env`, one verified archive directory, the two fixed route
authorities, the validated 1650 reference path, and operator-confirmed RDS free bytes.

`preflight` explicitly changes its transaction to read-only. It must show:

- both current exact routes/versions or an earlier validity explained by a prior COMPLETE import;
- first LIVE response by route;
- exact application Flyway V1..V12 version/name/checksum/success set; extra or missing rows fail;
- at least 5 GiB free RDS storage and enough room for the tool's conservative peak estimate;
- encrypted RDS/backups/snapshots and encrypted EC2 staging, attested outside SQL;
- acceptable CPU credits, freeable memory, write latency, queue depth, connections, and live lag.

```bash
java -jar "$MIGRATION_JAR" preflight --config "$ACADEMY_CONFIG"
```

No remote preflight was run during implementation. If SSH/SSM access is unavailable, record the
missing evidence and stop. Do not replace it with assumptions.

## Phase 4: ACADEMY_SCHEMA approval

After preflight review and a separately approved recoverable RDS backup/snapshot, issue a short-lived
`ACADEMY_SCHEMA` receipt bound to database identity. Apply only the tool's separate additive history:

```bash
java -jar "$MIGRATION_JAR" schema \
  --config "$ACADEMY_CONFIG" \
  --approval "$ACADEMY_SCHEMA_APPROVAL"
```

Application Flyway V1..V12 must remain unchanged. Verify the new history table reports V1, V2 and V3.

Import needs no write fence. The importer inserts `S3_BACKFILL` batches whose latest
`response_received_at` is earlier than 2026-09-02T13:20:01Z, and the worker's forecast queue predicate requires
`response_received_at >= now() - forecast.staleness` (`FORECAST_STALENESS`, default `5m`). The bound is
enforced in code, not requested by this document: `ForecastProperties` rejects any `forecast.staleness`
above its `MAX_STALENESS` of one hour and the worker fails to start, so no configuration can widen the
window past an hour while forecasting is enabled. Every imported row is hours older than that bound at cutover time, so the online
queue cannot pick one up at any permitted value. The isolation argument rests on that enforced bound,
not on an origin predicate: the worker neither reads nor knows the `ingestion_origin` column.
Reconciliation still asserts the real invariant, zero `seat_forecast` rows referencing imported
observations.

Raising `FORECAST_STALENESS` still costs something below the cap. The queue is ordered by
`response_received_at` ascending and each cycle takes at most `batch-limit` batches per active route
(20 each, so 40 across the two configured routes), so a wider window lets the oldest eligible batches
fill those slots ahead of ones that just arrived. Keep the operational value at minutes. The cap exists
so the invariant does not depend on operator discipline; it is not headroom to spend.

After import, `ForecastJob` warns once a minute that batches older than the window are being left
without a forecast, naming the oldest one. Its query
(`findOldestAwaitingForecastAt`) filters on outcome but not on `ingestion_origin`, so the batch it names
is an imported August one and the warning does not clear. Treat it as expected after import rather than
as a live backlog signal, and use the predicate below to see whether any LIVE batch is actually being
left behind.

## Phase 5: ACADEMY_IMPORT approval and base import

Issue an `ACADEMY_IMPORT` receipt bound to the base manifest and database identity. Every write
command re-verifies schema, archive, approval, route authority, storage, and database access.

```bash
java -jar "$MIGRATION_JAR" stage --config "$BASE_IMPORT_CONFIG" --approval "$BASE_IMPORT_APPROVAL"
java -jar "$MIGRATION_JAR" validate --config "$BASE_IMPORT_CONFIG" --approval "$BASE_IMPORT_APPROVAL"
java -jar "$MIGRATION_JAR" merge --config "$BASE_IMPORT_CONFIG" --approval "$BASE_IMPORT_APPROVAL"
java -jar "$MIGRATION_JAR" reconcile --config "$BASE_IMPORT_CONFIG" --approval "$BASE_IMPORT_APPROVAL"
```

Review the canonical receipt using `RECONCILIATION.md`. Base must be COMPLETE before a terminal delta
can stage. A stopped stage/merge is resumed by rerunning the same command with the same manifest and
unexpired or reissued approval; do not edit checkpoints.

## Phase 6: terminal import and dataset seal

Issue a new `ACADEMY_IMPORT` receipt bound to the terminal manifest. Repeat stage, validate, merge,
and reconcile with the terminal config. Staging refuses a different/incomplete base. Reconciliation
creates the single dataset seal only after the terminal import becomes COMPLETE and verifies the
receipt/import/inventory identity.

Do not declare RDS training-ready until the seal joins both COMPLETE imports and all reconciliation
checks pass.

## Phase 7: statement timeout headroom for seed replay

`import.statement-timeout-seconds` is a config key (default 30, maximum 3600, `ImportSettings.java`)
and the tool overrides the server value with `SET LOCAL` in every transaction, so this one key bounds
every single statement it runs.

Seed replay is one per-route
`SELECT ... ORDER BY batch.response_received_at, batch.id, observation.source_row_number` read through
a `setFetchSize(5000)` cursor with `autoCommit=false`, so the timeout clock resets on each fetch round
and the elapsed time of the replay as a whole is not what the timeout measures. The exposed statement
is the first Execute. If the plan has to materialize the ordering, it sorts roughly 1.18M observations
for 3330 and 1.29M for 1650 after import, and at `work_mem=4MB` that becomes an external merge sort;
if that sort exceeds the timeout the statement dies with `57014 query_canceled`. The failure lands in
`seed-dry-run` or `seed-apply`, inside the cutover window.

Run this after import completes and before the cutover window:

1. In a read-only session, run `EXPLAIN (ANALYZE, BUFFERS)` on the same SQL shape and record time to
   first row and `Sort Method`, including whether it is an external merge and how much disk it uses.
   A preflight-time measurement over 60k rows proves nothing here; only the post-import 2.46M-row
   table is valid evidence.
2. Raise `import.statement-timeout-seconds` to a generous multiple of the measurement. Do not remove
   the bound. This timeout is also one of the guards that keeps a stuck statement from holding the
   cutover window open.
3. Check whether an index already supplies the ordering, in which case the risk disappears.
   `ix_batch_recent_history` covers `observation_batch (route_version_id, response_received_at)` and
   `ux_observation_source_row` covers `vehicle_observation (observation_batch_id, source_row_number)`,
   but `batch.id` is the second ORDER BY key and is in neither, so only the measured plan settles
   whether a full sort, an incremental sort, or no sort runs.
4. `reconcile` aggregates run under the same key, so review them at the chosen value too.
5. Check whether raising `work_mem` for the session is safe against FreeableMemory headroom
   (db.t4g.micro, server `work_mem=4096kB`).

## Throttle and stop conditions

Run one importer and one shard at a time. Defaults are 500 JDBC observation rows, at most 10,000
observations per transaction, 100 records per staging transaction, and 100 ms throttle. The EC2
operator stop threshold is 256 MiB RSS and the decompression buffer ceiling is 8 MiB. The RSS value
is not enforced by the JVM and has not been measured against the production 2.46-million-row replay;
monitor it externally and do not describe it as a proven ceiling.

Stop before the next shard when any of these occurs:

- EC2 available memory below 384 MiB or disk below 5 GiB;
- RDS free storage below 5 GiB or below the preflight headroom estimate;
- material CPU-credit, latency, queue-depth, connection, or live-collection degradation;
- route/content/authority mismatch, negative continuity gap, count mismatch, or changed archive;
- a positive continuity gap other than the verified first-LIVE values (3330 7.075820 seconds, 1650
  1.986941 seconds); the current reconciler rejects overlap but has no general positive-gap maximum;
- approval expiry or any unexpected output surface containing sensitive data.

Expected RDS import time is 20–90 minutes, with a degraded upper envelope of four hours. Persistent
RDS growth is roughly 1.05–1.55 GB including provenance/index allowance; WAL and temporary headroom
are separate. The measured base zstd stream is 19,589,490 bytes and terminal delta is expected around
0.95–1.4 MB.

## Rollback

Before derived forecasts are created from imported rows, run a dry-run:

```bash
java -jar "$MIGRATION_JAR" rollback \
  --config "$IMPORT_CONFIG" \
  --manifest-sha256 "$MANIFEST_SHA" \
  --execute false
```

Review target counts and LIVE invariants. Executed rollback requires a new manifest-bound
`ACADEMY_ROLLBACK` receipt:

```bash
java -jar "$MIGRATION_JAR" rollback \
  --config "$IMPORT_CONFIG" \
  --manifest-sha256 "$MANIFEST_SHA" \
  --execute true \
  --approval "$ACADEMY_ROLLBACK_APPROVAL"
```

Rollback terminal before base. An active dependent delta or any forecast referencing imported rows
blocks rollback. Terminal rollback removes its dataset seal. Neither rollback nor import touches
pre-existing LIVE observations. Execute runs at `REPEATABLE READ`, so before/after LIVE invariants
share one snapshot while concurrently committed collector rows remain preserved outside that snapshot.

## Formal model cutover and temporary cleanup

This is separate from observation import. The delivered candidate identity and its verified file
digests are fixed in `COMBINED-CUTOVER-RUNBOOK.md`. It is classified `PROVISIONAL_19D` with
`FAIL_N_LT_30`; the user explicitly accepted that release risk at 2026-09-03 01:15 KST. That decision
does not approve deployment, seed writes, or cleanup. `MODEL_BUNDLE_PROMOTE_ON_START=false`, so the
approved promotion path is a one-shot `true` restart followed immediately by restoring `false`.

The fixed candidate values are release ID `v41b-8194bde56d86f365afd6`, bundle digest
`9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632`, manifest SHA-256
`8a39fbf8a828e8e490d500d9b99b6235c8fe7cff896f1986e9f186ddee3c33e4`, weights SHA-256
`5b906da96d7b3e4b45c5e9d970df41c499f0d457756b853b613f672f589a3228`, and aggregate seed gzip
SHA-256 `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4`.

Derived writes stop by restarting the worker, not by a write fence. With `forecast.enabled=false` the
`ForecastScheduleConfig`, `ForecastJob`, `ForecastBatchWriter`, `ArrivalLabelJob` and
`StopDemandStatisticsJob` beans do not start at all, while collection keeps running because it reads
only `collection.enabled`. Each derived write is one transaction, and the restart is stop, confirm the
process exited, then start, so no half-written batch survives it.

The activation-last sequence is:

0. Set `FORECAST_ENABLED=false` in `worker.env` and restart the worker. Read `worker.env` back to
   confirm the setting landed and confirm the systemd MainPID changed across the restart, then confirm
   health is UP, that `observation_batch` keeps growing, and that `max(seat_forecast.generated_at)`,
   `max(seat_forecast.scored_at)` and `max(stop_demand_statistics.computed_at)` all stop advancing for
   at least two cycles. The three clocks alone only show that nothing was written recently, which a
   thin dispatch window also looks like; the env read-back and the new MainPID are what prove the
   running process was started with forecasting off.
1. Run `temp-pause`. It refuses with `TEMP_FORECAST_WRITES_NOT_QUIESCENT` unless those three clocks are
   already older than `clock_timestamp() - 120 seconds`, then fixes DB `FINAL_CUTOVER_AT` and the
   observation high-water in one transaction. It is a boundary ledger, not a fence: the advisory lock
   it takes only serializes tool commands against each other, and the worker never reads
   `forecast_cutover_control`.
2. Freeze the exact temporary generation set at that boundary. Show the dry-run digest before the
   separately approved execute call.
3. Dry-run and separately approve bounded cleanup. Delete only deployment-1 forecasts and the frozen
   temporary statistics set; preserve observations, `forecast_completed_at`, and deployment lineage.
   Re-run cleanup to prove zero targets.
4. Run `seed-dry-run`. It verifies the 45,224-row payload and source-cutoff parity, performs
   `REPEATABLE READ READ ONLY` observation-only full replay through the fixed boundary, and records
   exact decimal `F_T-S` and merged hashes.
5. Under a plan-bound `ACADEMY_SEED_APPLY` approval, revalidate the plan, write exact `numeric` totals,
   read them back, and materialize one official `observed-max-capacity-v1` generation for each route
   with `data_until=FINAL_CUTOVER_AT`. Cell hashes and frozen-key intersection must pass.
6. Verify the first official generation while everything is still read-only: route coverage
   `[1650, 3330]`, `data_until=FINAL_CUTOVER_AT`, and the receipt digests.
7. Only now set `MODEL_BUNDLE_PROMOTE_ON_START=true` and `FORECAST_ENABLED=true` and restart under the
   separate promotion approval. Verify the exact formal identity as sole ACTIVE, id 1 RETIRED, then
   immediately restore `MODEL_BUNDLE_PROMOTE_ON_START=false` in `worker.env`; that restore needs no
   further restart. Forecasting resumes at this restart.
8. Run normal `temp-unpause`; it links the formal deployment to the applied seed ledger and closes the
   boundary ledger.
9. After each route has a new formal forecast, run `seed-verify` and require official-first-or-later
   non-frozen generation references, then require fresh board 200.

Board reads the newest batch carrying `forecast_completed_at` and its freshness window is five minutes,
so roughly five minutes after the step 0 restart a `NO_RECENT_OBSERVATION` 503 is expected even though
collection never stopped. After the step 7 restart the staleness window skips every batch that piled up
during the cutover, so serving returns 200 within one cycle instead of waiting for a backlog to drain.
Those skipped batches keep `forecast_completed_at IS NULL` permanently; that is intended, and they are
identified by `forecast_completed_at IS NULL AND response_received_at < now() - interval '5 minutes'`.

There is no finally or watchdog that clears a durable pause, and the pause no longer blocks the worker,
so closing an interrupted run means checking both the ledger state and that the worker is still on
`FORECAST_ENABLED=false`. Before formal activation, any failure may use separately approved
`temp-unpause --recovery true` only after confirming id 1 is still sole ACTIVE; that path rolls back an
applied seed and marks the freeze aborted. After formal activation, leave the worker on
`FORECAST_ENABLED=false` and obtain a separate approval to re-promote the temporary bundle through the
startup path. Never update ACTIVE or `writes_paused` manually.

Example command shapes:

```bash
<APPROVED_FORECAST_ENABLED_FALSE_RESTART>
<VERIFY_HEALTH_UP_COLLECTION_ADVANCING_AND_DERIVED_CLOCKS_STOPPED>

java -jar "$MIGRATION_JAR" temp-pause \
  --config "$CUTOVER_CONFIG" \
  --approval "$CUTOVER_APPROVAL"

java -jar "$MIGRATION_JAR" temp-freeze \
  --config "$FREEZE_DRY_RUN_CONFIG" \
  --execute false

java -jar "$MIGRATION_JAR" temp-freeze \
  --config "$FREEZE_EXECUTE_CONFIG" \
  --execute true \
  --approval "$FREEZE_APPROVAL"

java -jar "$MIGRATION_JAR" temp-cleanup \
  --config "$CLEANUP_DRY_RUN_CONFIG" \
  --execute false \
  --delete-batch-rows 1000

java -jar "$MIGRATION_JAR" temp-cleanup \
  --config "$CLEANUP_EXECUTE_CONFIG" \
  --execute true \
  --delete-batch-rows 1000 \
  --dry-run-receipt "$CLEANUP_DRY_RECEIPT" \
  --approval "$CLEANUP_APPROVAL"

java -jar "$MIGRATION_JAR" seed-dry-run \
  --config "$SEED_DRY_CONFIG" --seed "$SEED_GZIP" \
  --seed-receipt "$SEED_SOURCE_RECEIPT" --output "$SEED_PLAN"

java -jar "$MIGRATION_JAR" seed-apply \
  --config "$SEED_APPLY_CONFIG" --seed "$SEED_GZIP" \
  --seed-receipt "$SEED_SOURCE_RECEIPT" --plan "$SEED_PLAN" \
  --approval "$SEED_APPLY_APPROVAL"

<APPROVED_PROMOTE_ON_START_TRUE_AND_FORECAST_ENABLED_TRUE_RESTART>
<VERIFY_FORMAL_ACTIVE_AND_RESTORE_PROMOTE_ON_START_FALSE>

java -jar "$MIGRATION_JAR" temp-unpause \
  --config "$CUTOVER_CONFIG" \
  --recovery false \
  --approval "$UNPAUSE_APPROVAL"

java -jar "$MIGRATION_JAR" seed-verify \
  --config "$VERIFY_CONFIG" --plan "$SEED_PLAN" \
  --cleanup-receipt "$CLEANUP_RECEIPT" --cleanup-noop-receipt "$CLEANUP_NOOP_RECEIPT" \
  --cleanup-approval "$CLEANUP_APPROVAL" --seed-apply-approval "$SEED_APPLY_APPROVAL" \
  --provisional-approval "$PROVISIONAL_APPROVAL" \
  --promotion-approval "$PROMOTION_APPROVAL" --promotion-receipt "$PROMOTION_RECEIPT" \
  --output "$FINAL_SEED_CUTOVER_RECEIPT"
```

Use a different `import.receipt-output` path for every dry-run and execution because secure receipt
creation never overwrites an existing file.

## Archive retention and deletion

No script automatically deletes local/remote archives, `.incoming` files, receipts, Lambda, or S3.
After import and training snapshot receipts are verified, list exact paths and hashes and request a
separate deletion approval. Retaining Lambda/S3 and keeping the personal collector schedule disabled
are the current policy.

Complete the separate [LIVE-EXECUTION-CHECKLIST.md](LIVE-EXECUTION-CHECKLIST.md) before any live
phase.
