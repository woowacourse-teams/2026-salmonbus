# Provenance

## Authority and implementation snapshots

Priority is the current dev API/worker implementation, then the fitting/backfill cross-spec, then historical
practice. The authoritative consumer is commit
`d856d10819bf1d018ad43fa63714cc348f1fc643` in `/Users/idonghun/IdeaProjects/2026-salmonbus`.
No file in that worktree was changed.

The investigation began against predecessor `ed2cf742b0db368d7cf6eae2556b36bc156a5e72`. The only diff to the
active authority is build/deploy tooling plus `application.yml`, which adds route 1650 to collection. Forecast
10s, settlement 60s, statistics 6h, all Java model/label/cell/loader sources and DB migrations are unchanged.
The predecessor is historical provenance only; final manifest identity and Java validation use d856.

The private, in-process S3 validator reused by the builder comes from salmonbus-analysis commit
`c5ff99da81ed0af9916bd5aa5115d89f49258e2c`. Its record/raw body, HMAC, envelope, route and station-roster
checks are reused; raw identifiers never cross the process boundary.

The cross-spec was read from the following uncommitted be-models workspace files, so its file SHA-256 values
in the package manifest are stronger evidence than that worktree's unrelated HEAD:

- `validation/v4-1/source-to-feature.md`
- `validation/v4-1/deterministic-backfill-spec.md`
- `validation/v4-1/bundle-and-fitting-contract.md`
- `validation/v4-1/ExternalV41BundleContractTest.java`
- `validation/v4-1/verify-with-dev-java.sh`

The manifest `sourceCommit` is the authoritative consumer commit because this assignment forbids creating an
artifact commit. The exact uncommitted builder and numerical implementation are bound by SHA-256 in
`processed/build-receipt.json`. This is an explicit semantic limitation: `sourceCommit` is not a commit that
contains these handoff scripts.

## Frozen source closure

Training prediction dates are KST 2026-08-14 through 2026-09-01. The primary closure contains 142,129
accepted record documents, 142,004 accepted raw bodies, 2,324,399 normalized observations and 2,206,270,988
inventory bytes. It quarantines 27 records containing 27 mismatching station rows (545 whole-record
observations), accepts four validated midnight
partition crossings, and has no invalid record. The accepted closure digest is
`3cbdc35f50236ca92c3545ac7262762fc05d57933a712fa713830e23771eb0f0`.

The stated “month+” premise is not true for the primary `records/` and `raw/` prefixes at this cutoff: only
19 fully ended KST date partitions exist. The N>=30 release-qualification rule therefore fails. All 19 date
partitions are still used for the requested research build; none is shortened to make a gate pass.

The last prediction date has a separate label-only watermark through 2026-09-02 02:15 KST
(2026-09-01T17:15:00Z), equal to date end plus 2 hours plus max G=600 seconds plus max S=300 seconds. It adds
376 record/raw pairs and 1,544 observations, opens zero predictions, and has digest
`59c0e9613510d22e8e27753b661cd2de3e3b28defe2958ff0d803683183db6f6`. This prevents late 9/1 forecasts
from being mislabeled merely because the first inventory ended at midnight.

The migration audit first fixed a global half-open source/target authority boundary at the first academy RDS
response. That receipt remains historical evidence only. After d856 enabled live 1650, the final selection uses
separate route boundaries: 3330 contributes 3,249 records/58,234 observations before
2026-09-02T10:27:52.390820Z, and 1650 contributes 3,815 records/78,675 observations before
2026-09-02T12:49:33.041299Z. There is no S3 record inside the 1650 bracket ending at its first forecast at
12:49:35.539364Z, so membership is invariant across that uncertainty. The coefficient fit and quality split
still use only the 19 completed prediction dates; the partial catch-up date opens seed-only structural h1 rows.

The personal Scheduler was disabled by the user at 2026-09-02T22:18:57.240+09:00 and was read-only verified
as `DISABLED` at 13:19:31Z. Two post-disable S3 observations, at 13:20:51Z and 13:22:26Z, were identical:
15,750 objects, 140,161,072 bytes and object-inventory-v1 SHA-256
`f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e`. The last object times were
13:20:01Z for 3330 and 13:20:02Z for 1650, proving the first observation included the final in-flight delivery.
The final content pass at 13:22:26Z found zero invalid or ambiguous records. Its 7,876 records close against
7,874 raw documents plus two declared raw-less records; one 1650 record/24 observations remains explicitly
quarantined for the known station mismatch. No accepted catch-up object arrived after the earlier 13:00Z
audit: both route digests and counts are identical, so the measured late-object count is zero.

This `FINAL_FREEZE_CLOSED` receipt is the bundle/seed and migration source authority. Base plus catch-up is
149,193 batches and 2,461,308 observations, with composite closure SHA-256
`75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7`. The 811 post-boundary S3
records contain 14,924 accepted observations and remain continuity/dedupe evidence only; they are excluded
from coefficient fitting and migration inserts because academy RDS owns that interval. Later zero-observation
quota failures are retained in the frozen inventory but do not change the selected catch-up.

Detailed per-day counts, freeze instants and privacy assertions are in `processed/source-audit.json`,
`processed/label-watermark-audit.json`, `processed/final-s3-freeze-closed.json`,
`processed/final-source-closure.json` and `processed/source-load-receipt.json`. The similarly named
`processed/final-s3-freeze.json` is explicitly marked as a pre-disable, non-authoritative 13:15Z snapshot.
The cross-worktree audit files and exact SHA-256 adoption are summarized in
`processed/migration-audit-adoption.json`.

Private source vehicle IDs have the same meaning and format as academy `vehicle_observation.vehicle_id`.
Under the user's final identity decision they may be used only inside the private replay process to preserve
journey continuity. This builder validates their HMAC correspondence, maps them to process-local integers and
emits neither representation. A future migration may carry raw vehicle ID only as encrypted internal data with
0600 files, 0700 directories and no log/fixture/document output. Plate values remain null in backfill and only
in source S3; raw bodies, pseudonyms and service keys are never migrated.

The model coordinate system is the source route reference itself. Target RDS now has 3330 version id 1
(valid from 2026-09-02T10:27:51.330754Z) and 1650 version id 2
(valid from 2026-09-02T12:49:33.041299Z). Both match source order/ID, turn and non-boarding policy exactly, but
their current validity does not cover historical dates. That absence does not discard source rows. By user
decision, a future approved migration may extend each current version backward to its route's earliest accepted
source timestamp while preserving original valid-from values in a rollback ledger; this package performs no
such write. Mapping identity still includes validity as well as content.

## Academy RDS boundary and temp exclusion

The academy RDS baseline was queried by the user through an EC2-internal read-only transaction. Before the
smoke deployment it had 303 observation batches, 8,896 vehicle observations and zero rows in
`seat_forecast`, `stop_demand_statistics` and `model_deployment`. This build never reads RDS rows as training
or seed input and performs no remote write or bundle transfer.

`model_deployment.id=1`, release `salmonbus-d57370be9195520e`, digest
`d57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a`, calculation version
`seat-feature-contract-v4-1-2026-09-02`, activated at 2026-09-02T11:55:04.729493Z, is
`TEMPORARY_SMOKE_ONLY`. The latest reported snapshot after d856 contained 151,523 forecast rows
(147,125 on route version 1 and 4,398 on version 2). Those rows, any later scored rows, and every statistics
generation carried by those forecasts are excluded from training, backfill, calibration, holdout and quality
claims. Underlying observations remain valid and deployment 1 must remain as RETIRED lineage.

The source watermark ends before temp activation, which structurally proves this build cannot contain temp
forecast material. `processed/input-exclusion-policy.json` and the negative-test receipt additionally bind the
future RDS anti-join. Because the current statistics writer can create `observed-max-capacity-v1` generations
from temp SETTLED rows, cleanup must freeze exact generation identities inside the activation-to-cutover
window; calculation-version-only cleanup is forbidden. See `seed/ATOMIC_CUTOVER.md` and
`seed/TEMP_EXCLUSION_READ_ONLY.sql`.

## Reproduction

With an existing read-only AWS profile and the two sibling repositories available:

```bash
scripts/run-source-audit.sh /path/to/salmonbus-analysis processed/source-audit.json
scripts/run-label-watermark-audit.sh /path/to/salmonbus-analysis processed/label-watermark-audit.json
scripts/run-route-catchup-audit.sh /path/to/salmonbus-analysis processed/final-s3-freeze-closed.json 2026-09-02T13:22:26Z
scripts/run-s3-freeze-observation.sh /path/to/salmonbus-analysis <OBSERVED_AT_UTC> <OUTPUT_JSON>
scripts/finalize_s3_freeze.py --base-audit processed/source-audit.json \
  --prior-route-audit processed/route-catchup-audit.json \
  --observation-one processed/s3-freeze-observation-1.json \
  --observation-two processed/s3-freeze-observation-2.json \
  --final-content-audit processed/final-s3-freeze-closed.json \
  --scheduler-disabled-at <DISABLED_AT> --scheduler-verified-at <VERIFIED_AT> \
  --output processed/final-source-closure.json
scripts/run-build.sh /path/to/salmonbus-analysis .
scripts/run-route-seed-refresh.sh /path/to/salmonbus-analysis .
scripts/finalize-and-validate.sh .
scripts/package_handoff.py --root .
```

The scripts contain no token, password, service key, connection string or plate/vehicle identifier. AWS calls
are S3 List/Get only. The build never writes Lambda, S3, EC2 or RDS and never transfers a bundle to a remote
host.
