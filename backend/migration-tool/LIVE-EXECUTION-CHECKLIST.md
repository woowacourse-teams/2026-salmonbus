# Live execution checklist

Every box is required unless it explicitly names a later phase. An unchecked item is a stop
condition. Completing this file does not itself grant approval.

## Local implementation

- [ ] HEAD/base and target dev commit are recorded; the four d856 sync files are byte-equal.
- [ ] `:migration-tool:test`, `:worker-app:test`, and `clean build` pass from a clean build directory.
- [ ] Migration tests include base/terminal manifest chaining, interruption/resume, duplicate replay,
  concurrent LIVE insert, route validity rollback, terminal seal rollback, private-identity rejection,
  additive schema without application Flyway history change, application checksum drift rejection,
  default `LIVE` ingestion origin, canonical roster station order, populated-table DDL lock timeout,
  full replay cutoff parity, persisted pause, cleanup dry-run receipt mismatch rejection, provider seed
  replay/apply/rollback/reapply, final serving references, exact temp cleanup, and cleanup idempotency.
- [ ] Worker tests cover the staleness window: `forecast.staleness` sets the queue's `notBefore` once
  per cycle for every route, and a batch older than that bound is never handed out.
- [ ] `git diff --check`, shell syntax, JSON parsing, and secret-pattern scans pass.
- [ ] No commit, push, PR, merge, deploy, or remote mutation has occurred without its own approval.

## Source authority

- [ ] Target dev is `d856d10819bf1d018ad43fa63714cc348f1fc643`.
- [ ] Immutable base inventory is
  `db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9`.
- [ ] Terminal partition inventory is
  `f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e`.
- [ ] Full-history dataset-seal authority is
  `ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8`.
- [ ] Data-analysis source closure is
  `75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7`.
- [ ] `jq -e --arg expected '75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7' '.schemaVersion == "v4-1-final-source-closure-v1" and .sourceClosureSha256 == $expected' /Users/idonghun/paseo/workspaces/2026-salmonbus/data-analysis/handoff/coefficient-bundle-v4-1/processed/final-source-closure.json`
  succeeds before terminal receipt/archive build; the tool itself reads the value from the terminal receipt.
- [ ] Two source closure observations remain stable, record/raw bijection errors are zero, and late
  immutable-base objects are zero.
- [ ] Personal collector schedule remains disabled; Lambda and S3 remain retained.
- [ ] No GBIS call is part of export, audit, migration, or training.

## Privacy and local files

- [ ] Actual vehicle values can appear only inside protected archive/staging rows and private RDS.
- [ ] No manifest, receipt, fixture, documentation, stdout, or log contains an actual vehicle value.
- [ ] Plate, source HMAC/pseudonym, raw body/envelope, source object location, service key, DB secret,
  presigned URL, and SSH private-key content are absent from artifacts and transcripts.
- [ ] `umask 077`; every archive/staging directory is 0700 and file/partial is 0600.
- [ ] Source inventory and approval/terminal receipts are private regular files, not symlinks.
- [ ] Archive deletion is not scheduled or automated.

## SOURCE_EXPORT approval and archive

- [ ] A user/operator issued an unexpired canonical `SOURCE_EXPORT` receipt.
- [ ] Base config uses KST 2026-08-14..2026-09-01 and expected
  142,129 batches / 2,324,399 observations / 27 quarantine / 545 quarantine observations.
- [ ] Terminal config uses the verified base inventory, KST through 2026-09-02, and expected
  7,064 / 136,909 accepted, 1 / 24 quarantined, 811 / 14,924 overlap.
- [ ] Route authorities are 3330 `2026-09-02T10:27:51.330754Z` and 1650
  `2026-09-02T12:49:33.041299Z`.
- [ ] Terminal receipt uses the full-history SHA as `finalInventorySha256` and carries the exact
  partition/base/composite cross-references.
- [ ] Base and terminal `inventory`/`archive-build` outputs report their exact expected aggregates and
  `complete=true`.
- [ ] Exit code 0 alone is not accepted: each archive-build receipt has `complete=true` and an empty
  `rejectsByCode`; `SOURCE_DUPLICATE_SEMANTIC_BATCH` is a build reject that otherwise blocks only at stage.
- [ ] `archive-verify` succeeds for both; manifest, compressed/uncompressed shard hashes, totals,
  rosters, canonical JSON, file set, and modes match.

## Transfer access and approval

- [ ] Academy access method is explicitly approved and available; no repeated SSH probes were made.
- [ ] SSH uses a pinned host key, BatchMode, IdentitiesOnly, and a 10-second connect timeout; or an
  already-approved SSM path provides equivalent EC2-local access.
- [ ] A separate unexpired `RSYNC_ARCHIVE_TRANSFER` receipt is bound to each manifest.
- [ ] Host/user/key/known_hosts/remote path are supplied only by private environment references.
- [ ] Transfer remains under the approved migration root and lands as `.incoming`.
- [ ] EC2 re-verifies local manifest/shard hashes and schema before any final rename.
- [ ] Presigned HTTPS, if separately approved as fallback, uses private stdin and is never logged.

## Academy read-only preflight

- [ ] Preflight transaction reports `readOnly=true`.
- [ ] `HistoricalSchema` exact gate reports application Flyway versions/names/checksums/success equal
  to the expected V1..V12 set; extra, missing, failed, or changed rows stop execution.
- [ ] Current routes are exactly version 1 for 3330 and version 2 for 1650, or earlier validity is
  explained by a COMPLETE base import ledger.
- [ ] Route content/turn/stops/boarding/departure data match the verified references.
- [ ] Exact first LIVE observation by route is captured as an aggregate; no identity value is output.
- [ ] RDS, backups/snapshots, and EC2 staging are encrypted at rest.
- [ ] A recoverable backup/snapshot and restore owner are identified and separately approved.
- [ ] RDS free storage is at least 5 GiB and exceeds the conservative preflight headroom requirement.
- [ ] CPU credits, freeable memory, write latency, queue depth, connections, and live lag are safe.
- [ ] EC2 has at least 384 MiB available memory and 5 GiB free disk.
- [ ] The 256 MiB replay RSS value is treated as an externally monitored stop threshold, not a
  code-enforced or production-measured ceiling.
- [ ] Reconciliation reports exactly 7.075820 seconds for 3330 and 1.986941 seconds for 1650; another
  positive gap stops execution because the reconciler has no general positive-gap maximum.

## ACADEMY_SCHEMA

- [ ] A separate unexpired `ACADEMY_SCHEMA` receipt is bound to the database identity.
- [ ] Tool Flyway V1/V2/V3 is reviewed as additive and uses `historical_import_schema_history`.
- [ ] Worker code carrying the staleness window is deployed before import: `forecast.staleness`
  (`FORECAST_STALENESS`, default `5m`) is in effect and the pending-forecast queue applies
  `response_received_at >= :notBefore`. Every imported row is older than
  2026-09-02T13:20:01Z, so no backfill batch can fall inside the window.
- [ ] Schema execution reports only the expected migrations; application Flyway history remains intact.
- [ ] An index that already serves
  `ORDER BY batch.response_received_at, batch.id, observation.source_row_number` is looked for first;
  if one exists the seed replay needs no sort and the timeout question is closed on that evidence.
- [ ] Otherwise `import.statement-timeout-seconds` is raised from measurement, not from guesswork:
  after import, a read-only `EXPLAIN (ANALYZE, BUFFERS)` on the seed replay shape records time to
  first row and `Sort Method`, including external merge and its disk use. The preflight run over
  roughly 60k rows does not count; only the post-import 2.46M-row measurement does.
- [ ] The raised value is a generous multiple of that measurement and stays finite. It is never set
  unbounded, because the same timeout is what bounds the cutover window; a first Execute that exceeds
  it fails with `57014 query_canceled` inside `seed-dry-run` or `seed-apply`.
- [ ] `reconcile` aggregates run under the same value and are measured with it. Any session `work_mem`
  raise above the db.t4g.micro default of 4096kB is checked against FreeableMemory headroom first.

## Base ACADEMY_IMPORT

- [ ] A manifest/database-bound `ACADEMY_IMPORT` receipt is issued.
- [ ] `stage` totals match the base manifest and every shard checkpoint is valid.
- [ ] `validate` reports no unexpected reject/identity/route conflict and only planned classifications.
- [ ] Route version count remains 2; only id 1/id 2 `valid_from` may be extended.
- [ ] `merge` runs one importer, one shard, and at most 10,000 observations per transaction.
- [ ] Live health/load is checked between shards; stop thresholds are enforced.
- [ ] `reconcile` marks base COMPLETE and passes all `RECONCILIATION.md` invariants.

## Terminal ACADEMY_IMPORT and seal

- [ ] A new manifest/database-bound `ACADEMY_IMPORT` receipt is issued.
- [ ] Previous manifest is the exact COMPLETE base and its inventory is the pinned immutable base.
- [ ] Stage/validate/merge/reconcile pass with terminal-delta expected counts.
- [ ] Terminal reconciliation records all four closure digests and marks the import COMPLETE.
- [ ] Exactly one dataset seal exists and joins the same terminal manifest, receipt, import batch, and
  full-history inventory.
- [ ] LIVE rows are unchanged; no S3_BACKFILL batch appears in the online pending-forecast query,
  because every imported `response_received_at` sits outside the five-minute staleness window.

## Rollback readiness

- [ ] Base and terminal rollback dry-runs are captured before derived forecasts use imported rows.
- [ ] Rollback order is terminal before base; dependent-delta and imported-forecast blockers are known.
- [ ] A separate `ACADEMY_ROLLBACK` receipt template is ready but not pre-authorized.
- [ ] Route original `valid_from` values and aggregate LIVE before/after invariants are in the ledger.
- [ ] No rollback command is treated as cleanup approval.

## RDS training handoff

- [ ] Terminal seal and both COMPLETE imports are visible in one REPEATABLE READ READ ONLY snapshot.
- [ ] Snapshot ID, high-water cursor, counts, Flyway checksums, manifest chain, route bindings, and
  exclusion policy are captured in canonical aggregate receipt.
- [ ] Trainer reads LIVE and S3_BACKFILL history causally and emits no private identity.
- [ ] Temporary forecast and exact frozen statistics generations contribute zero official rows.
- [ ] Final data-analysis release ID, bundle digest, Java parity, holdout, and aggregate seed receipts
  are delivered and independently verified.
- [ ] Full training is scheduled off the 1.8-GiB/no-swap academy EC2.

## Formal cutover and cleanup

- [ ] `PROVISIONAL_19D` and the 2026-09-03 01:15 user override are recorded; no integrity gate is waived.
- [ ] Formal promotion has its own approval and uses one-shot
  `MODEL_BUNDLE_PROMOTE_ON_START=true`; the plan restores `false` immediately after the restart.
- [ ] `FORECAST_ENABLED=false` is set in `worker.env` and the worker is restarted before anything else
  in this section; health is UP and `observation_batch` keeps growing, so collection never stopped.
- [ ] `max(seat_forecast.generated_at)`, `max(seat_forecast.scored_at)` and
  `max(stop_demand_statistics.computed_at)` have all stopped advancing for at least two cycles.
- [ ] `temp-pause` passes its quiescence gate and records DB `FINAL_CUTOVER_AT` and observation
  high-water. It is a boundary ledger, not a fence; its advisory lock only serializes tool commands and
  the worker never reads `forecast_cutover_control`.
- [ ] Throughout the window, raw observation counts keep increasing and the three derived-write clocks
  stay frozen. Batches skipped by the staleness window keep `forecast_completed_at IS NULL` permanently
  and are identified by `forecast_completed_at IS NULL AND response_received_at < now() - interval '5 minutes'`.
- [ ] Operators know board returns `NO_RECENT_OBSERVATION` 503 roughly five minutes after the
  `FORECAST_ENABLED=false` restart, and that it recovers within one cycle after the re-enable restart
  because the staleness window skips the accumulated backlog rather than draining it.
- [ ] There is no finally/watchdog auto-unpause, and the durable pause no longer blocks the worker, so
  closing an interrupted run requires checking both the ledger and that the worker is still on
  `FORECAST_ENABLED=false`. A private, separately approved `temp-unpause --recovery true` command is
  ready for every pre-activation failure.
- [ ] `temp-freeze` dry-run/execute bind the exact temporary generation set to T/high-water while temp id
  1 remains sole ACTIVE.
- [ ] Cleanup dry-run identity/counts are shown to the user and a new deletion approval is bound to
  the canonical dry-run receipt SHA; execute receives that same receipt through `--dry-run-receipt`.
- [ ] Cleanup deletes only deployment-1 forecasts and exact frozen generation rows in bounded chunks.
- [ ] Observation counts/time bounds and `forecast_completed_at` markers are unchanged; deployment 1
  remains ACTIVE through cleanup and its lineage remains after later retirement.
- [ ] Cleanup rerun is a no-op before seed apply.
- [ ] `seed-dry-run` uses `REPEATABLE READ READ ONLY`, proves route-cutoff `F_C==S`, computes `F_T-S`,
  and binds exact numeric counts/key/row/aggregate SHA to a private plan.
- [ ] `seed-apply` revalidates the same plan, reads back exact `numeric` rows, materializes exactly one
  official first generation per route with `data_until=T`, and finds frozen-key intersection zero.
- [ ] Only after official-generation verification is the worker restarted with
  `MODEL_BUNDLE_PROMOTE_ON_START=true` and `FORECAST_ENABLED=true`; formal release, bundle digest,
  calculation version, predecessor id 1, ACTIVE/RETIRED states and the immediate restore of
  `MODEL_BUNDLE_PROMOTE_ON_START=false` all match. The restore needs no further restart.
- [ ] Normal `temp-unpause` links the formal deployment to the seed ledger and closes the boundary ledger.
- [ ] `seed-verify` emits a private `v4-1-seed-cutover-receipt-v1`; each route has a new formal forecast
  referencing the official-first-or-later, non-frozen generation and board returns fresh 200.
- [ ] If formal validation fails after activation, the worker stays on `FORECAST_ENABLED=false` and only
  a separately approved temporary startup promotion may recover service; `--recovery true` is not used
  after activation.

## Completion and retention

- [ ] EC2-only collection recovery for both routes is independently confirmed when read-only access is
  available; personal S3 remains unchanged after terminal LastModified.
- [ ] Quota-outage LIVE batches remain preserved and separately counted.
- [ ] Final report lists artifacts, commands, suite/test counts, resource envelope, unresolved access,
  proposed commits, and base delta.
- [ ] Any local/remote archive or receipt deletion has a separately approved exact path/hash list.
