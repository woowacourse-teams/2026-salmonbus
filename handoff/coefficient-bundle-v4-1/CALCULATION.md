# Calculation and replay

## Source normalization and target rows

Each frozen record/raw pair is checked byte-for-byte before use. A row is storable only when station sequence
and station ID exist and `stateCd` is 0, 1 or 2. The current Java normalization is reproduced as:

```text
passedStopOrder = stationSeq - 1  when stateCd == 1
                  stationSeq      when stateCd is 0 or 2
known seats      = remainSeatCnt >= 0
known crowd      = crowded in {1,2,3,4}; every other value is unknown
```

A station-ID mismatch quarantines the record. The replay retains an empty structural batch at that timestamp
so a trajectory cannot connect across an untrusted response. A forecast target exists only for a current row
with known seats and a positive as-of vehicle maximum, a boarding stop, and raw roster distance 1..12. Via
stops consume distance but are not targets; no turn-around wrap is performed.

The private journey join uses exact raw vehicle-ID equality, matching the current academy column semantics, and
immediately maps that value to a process-local integer. The accompanying source HMAC is checked only for
one-to-one integrity; it is not the journey key. Neither value is persisted. Same-timestamp S3/DB ordering
sensitivity is zero for this closure because no route has tied `response_received_at` values.

The 19 ended KST partitions open coefficient-training predictions. The following partial partition is split by
route: 3330 uses `response_received_at < 2026-09-02T10:27:52.390820Z`, while 1650 uses
`response_received_at < 2026-09-02T12:49:33.041299Z`. All 136,909 accepted pre-boundary observations remain
in trajectory/capacity/label history and successful batches open structural h1 rows for the aggregate seed,
but they open no coefficient-training or quality-evaluation row. The 14,924 accepted post-boundary overlap
observations are continuity/dedupe evidence only and are not used. This keeps the partial current date out of
the date split without throwing away verified source-owned history. The immutable selection is bound by
`processed/final-source-closure.json`; its post-disable stability delta and late accepted-object count are zero.

## Trajectory and 31 features

For each route-version, history is every batch with
`response_received_at > targetTime - 30 minutes` through the target cursor. Empty and failed batches remain in
the sequence. A vehicle chain breaks if it is absent from the immediately preceding batch, the passed order
regresses, or it jumps by more than one. Seat slope uses the immediately preceding linked known-seat row.
Full streak counts distinct passed stops backwards until a positive/unknown seat or a gap. The preceding
vehicle is the closest earlier first-entry at the current passed stop; any equal entry time makes it unknown.
Capacity is the positive maximum seats seen for that vehicle/route-version through the prediction cursor.

The feature order is exactly the current Java `SeatForecastDesignMatrix.COLUMN_NAMES`:

```text
constant, is_morning, is_evening, new_time_slot,
seats_left_ratio, is_full, low_seat_band,
crowd_level_1..crowd_level_4, maximum_seats_ratio,
seat_slope, seat_slope_missing, full_seat_streak,
preceding_vehicle_is_full, preceding_vehicle_seats_ratio, preceding_vehicle_missing,
route, stop_position_on_route, stop_position_basis_0..7,
fill_rate_score, net_boarding_segment_score, filled_by_neighbours
```

Morning is 07:00<=KST hour<09:00, evening is 17:00<=hour<20:00, and the source is batch
`response_received_at`. Low-seat width is 20, maximum-seat normalization is 68, and stop position is
`targetStopOrder/largestStopOrder`. Columns 4 (`new_time_slot`), 19 (`route`) and 21..28 (eight spline
columns), in one-based numbering, are always zero in current dev. Their hurdle/sign/bin coefficients are
forced to IEEE-754 `+0.0`; they are not estimated under invented semantics.

## generatedAt guard and labels

The unavailable historical `generatedAt` is represented by `t0=response_received_at`. For every G in normal
grid `[0,10,30,60,90,120]` seconds and stress grid `[300,600]`, the replay evaluates the continuous interval
using the finite set `{0,G}` plus each candidate timestamp crossing. A row survives only when every point has
the exact tuple `(SETTLED, arrival observation identity, source row identity, arrivalSeats)`.

At each point candidates must be strictly later than `t0+lag`. The current Java resolver then applies, in
order, gap>90 seconds -> LOST, passed-order regression -> LOST, target skip -> SKIPPED, exact target with known
seat -> SETTLED, exact target with unknown seat -> SEAT_MISSING, and unresolved wait>2 hours -> LOST. Every
non-SETTLED result is excluded rather than changed into a non-full label.

The deterministic G selection rule is fixed before metrics: choose the largest non-stress G for which all 24
route/horizon blocks retain at least 200 rows, two prediction dates and one positive-seat arrival. G>=90 cannot
retain a SETTLED endpoint under the same 90-second Java gap rule; the measured table and selected value are in
`processed/lag-sensitivity.json`. Coefficient quality is not used to choose G.

Label availability is `arrivalObservedAt + S`. The primary S is 60 seconds because that is the current
settlement interval and the cross-spec primary; S `[0,60,120,300]` is replayed for cell/feature and hurdle/anchor
sensitivity. No Platt, isotonic, temperature, intercept or PMF postprocessor is added.

## Chronological cell state

The state machine advances one way at 00:00, 06:00, 12:00 and 18:00 KST. At each instant it applies observations,
activates invariant h1 SETTLED rows with `scoredAt<=generation`, appends a generation when any row is active,
then lets prediction rows as-of join the newest `dataUntil<=response_received_at` generation. Before the first
generation, columns 29/30/31 are `0/0/1`. Model probabilities never enter cell state, so fixed-point iteration
count is exactly zero.

For each active h1 row at generation g:

```text
capacity(g)     = maximum known vehicle seats with observation time <= g
fill contribution = 1 - arrivalSeats/capacity(g)
net contribution  = predictionSeats - arrivalSeats
```

Rows first aggregate by stop and arrival UTC hour. Within each KST date/time slot, fill is
`sum(fill)/count` and net boarding is `sum(net)/sum(capacity)`. Cells are the unweighted mean of represented
date rates. Within a generation/time slot both values use population z-scores with standard-deviation floor
1e-9. Missing target cells use radius-4 inverse-distance-squared neighbours; the net segment sums direct cell
z-scores over `[target-horizon+1,target]` and divides by `sqrt(seen)`.

`seed/cell-hourly-aggregate.json.gz` stores only hourly sums/counts required to reconstruct that calculation.
Its authority extends independently through each route's half-open source/target cutoff; the last deterministic
6-hour feature generation before each cutoff is recorded separately. It is independent of live RDS statistics
and temp forecasts, and its source loader is pinned to the `FINAL_FREEZE_CLOSED` audit rather than the earlier
global-boundary receipt.

## Fitting and evaluation

Each route/horizon is fit separately in float64. Hurdle and sign heads use ridge=1 IRLS from zero, at most 60
iterations, weight floor 1e-9 and max-step tolerance 1e-10. The anchor fits
`arrival/capacity ~ [1,current/capacity]` by the 40-iteration L1 approximation with tolerance 1e-9. Positive-seat
rows define `residual=anchorSeats-arrivalSeats`. Sign heads are `residual==0` and `residual>0`; direction bins
use relative edges `[0,.03,.07,.12,.2,.32,.48,.7,1]`, direction minimum 50 and bin-positive minimum 10.
Unfitted bins have flag 0 and coefficient vector `+0.0`.

The available 19 date partitions cannot satisfy the prescribed N>=30 split. This package therefore applies a
declared provisional rule that preserves nine development dates and divides the remaining tail equally into
five calibration and five untouched holdout dates. It reports this as a release-gate failure, not as a quiet
contract change. Development uses expanding date-origin refits after all 24 blocks reach their bootstrap gate;
calibration chooses no Java-absent transformation; development+calibration is refit once for holdout; final
weights then refit all 19 prediction dates. Holdout metrics belong to the pre-final-refit candidate, not to the
weights that subsequently saw holdout labels.
