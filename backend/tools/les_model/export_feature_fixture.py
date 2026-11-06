"""Export original observations + saved samples; no training, no invented reference outputs.

This deliberately offers research events (original retrospective t_known) separately
from causal events, so arithmetic parity does not conceal the original look-ahead.
"""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

from contracts import SEAT_COLUMNS, OBS, EST, PATH, TODAY, TIME_BINS, sha256


def iso(value):
    value = pd.Timestamp(value)
    if value.tzinfo is None:
        value = value.tz_localize('UTC')
    return value.tz_convert('UTC').isoformat()


def clean(value):
    if isinstance(value, np.ndarray):
        return clean(value.tolist())
    if isinstance(value, (list, tuple)):
        return [clean(v) for v in value]
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, (float, np.floating)):
        return float(value) if np.isfinite(value) else None
    if isinstance(value, np.integer):
        return int(value)
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--route', default='3330')
    parser.add_argument('--day', default='2026-10-15')
    parser.add_argument('--max-origins', type=int, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Refusing to overwrite existing evidence')
    lab = args.run / 'lab'
    rebuild = lab / 'data/rebuild'
    metadata = json.loads((rebuild / 'routes.json').read_text())[args.route]
    key = metadata['key']
    statistics = json.loads((args.bundle / f'statistics-{args.route}.json').read_text())
    bands = {(x['stop'], x['band']): x['values'] for x in statistics['tables']['target_band']}
    spec = importlib.util.spec_from_file_location('original_stop_features', lab / 'scripts/stop_features.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    samples = pd.read_pickle(rebuild / f'samples_{args.route}_s19.pkl')
    rows = samples[(samples.date.astype(str) == args.day) &
                   (((samples.hour >= 6) & (samples.hour < 10)) |
                    ((samples.hour >= 17) & (samples.hour < 21)))].copy()
    rows['sample_row'] = rows.index
    rows = module.add_entry_columns(rows, metadata['stops'])
    obs = pd.read_pickle(rebuild / f'obs_{key}_trips.pkl')
    batches = pd.read_pickle(rebuild / f'batches_{key}.pkl').dropna(subset=['received']).sort_values(['received', 'batch'])
    batches['rank'] = np.arange(len(batches))
    obs = obs.merge(batches[['batch', 'rank']], on='batch').sort_values(['vid', 'rank']).drop_duplicates(['vid', 'batch'], keep='first')
    obs['fixture_id'] = np.arange(len(obs)) + 1
    obs['ns'] = obs.received.dt.as_unit('ns').astype('int64')
    cap = obs.seats.where((obs.seats > 0) & (obs.seats <= 70), 0).groupby(obs.vid.cat.codes).cummax()
    obs['cap'] = cap
    time_groups = obs.groupby('ns', sort=False).indices
    selected = []
    origins = set()
    source_ids = set()
    ambiguous = 0
    for index, sample in rows.iterrows():
        if len(source_ids) >= args.max_origins and int(sample.t_pred) not in origins:
            continue
        at_time = obs.iloc[time_groups.get(int(sample.t_pred), [])]
        matches = at_time[(at_time.passed == sample.target - sample.h) &
                          (at_time.seats == sample.current) & (at_time.cap == sample.capacity)]
        if len(matches) != 1:
            ambiguous += 1
            continue
        origin = matches.iloc[0]
        if pd.isna(origin.vid):
            ambiguous += 1
            continue
        if len(source_ids) >= args.max_origins and int(origin.fixture_id) not in source_ids:
            continue
        selected.append((sample, int(origin.fixture_id)))
        source_ids.add(int(origin.fixture_id))
        origins.add(int(sample.t_pred))
    if not selected:
        raise ValueError('No unambiguous source origins')
    cutoff = max(origins)
    obs = obs[obs.ns <= cutoff].copy()
    trip_first = obs.groupby('trip', observed=True).fixture_id.first().to_dict()
    trip_vehicle = obs.groupby('trip', observed=True).vid.first().to_dict()
    print('selected source origins', len(source_ids), 'cases', len(selected), 'full prefix rows', len(obs), flush=True)
    # Keep active continuity chains plus 90 minutes; older state is an explicit accumulator, never fake rows.
    window_start = min(origins) - 90 * 60 * 10**9
    recent = obs[obs.ns >= window_start]
    chain_first = obs.groupby('chain', observed=True).ns.min()
    retained_from = min(window_start, int(chain_first.reindex(recent.chain.unique()).min())) if len(recent) else window_start
    previous_vid = obs.vid.cat.codes.shift()
    previous_passed = obs.passed.shift()
    same = (obs.vid.cat.codes == previous_vid).to_numpy()
    delta = obs.passed.to_numpy() - previous_passed.fillna(0).to_numpy()
    linked = same & (obs['rank'].diff().fillna(0).to_numpy() == 1) & (delta >= 0) & (delta <= 1)
    seats = obs.seats.to_numpy()
    streak = np.zeros(len(obs))
    for i in range(len(obs)):
        if seats[i] == 0:
            streak[i] = streak[i-1] + (delta[i] != 0) if linked[i] and seats[i-1] == 0 else 1
    obs['full_streak'] = streak
    starts = (~same) | (delta != 0)
    entry_indices = np.maximum.accumulate(np.where(starts, np.arange(len(obs)), 0))
    obs['entry_ns'] = obs.ns.to_numpy()[entry_indices]
    before = obs[obs.ns < retained_from].groupby('vid', observed=True, dropna=False).tail(1)
    def observation(row):
        return dict(id=int(row.fixture_id), batch_id=int(row.batch), batch_rank=int(row.rank),
            received_at=iso(row.received), vehicle_id='\uffffles-anonymous-observations' if pd.isna(row.vid) else str(row.vid), passed_stop_order=int(row.passed),
            stop_order=int(row.stop_order), running_state=int(row.state), remaining_seats=None if pd.isna(row.seats) else int(row.seats),
            crowd_level=0 if pd.isna(row.crowd) else int(row.crowd))
    prefix = [dict(capacity=float(row.cap), full_streak=float(row.full_streak), previous=observation(row),
                   entry_at=iso(pd.Timestamp(int(row.entry_ns), tz='UTC')), trip_id=int(trip_first[row.trip])) for row in before.itertuples()]
    current_trips = set(obs.groupby('vid', observed=True).trip.last())
    obs = obs[obs.ns >= retained_from].copy()
    observations = []
    for row in obs.sort_values(['received', 'batch', 'vid']).itertuples():
        observations.append(dict(id=int(row.fixture_id), batch_id=int(row.batch), batch_rank=int(row.rank),
            received_at=iso(row.received), vehicle_id='\uffffles-anonymous-observations' if pd.isna(row.vid) else str(row.vid), passed_stop_order=int(row.passed),
            stop_order=int(row.stop_order), running_state=int(row.state), remaining_seats=None if pd.isna(row.seats) else int(row.seats),
            crowd_level=0 if pd.isna(row.crowd) else int(row.crowd)))
    events = pd.read_pickle(rebuild / f'events_{key}.pkl')
    events = events[events.t_known.dt.as_unit('ns').astype('int64') <= cutoff]
    old_recent_five = set(events[events.trip.isin(current_trips)].sort_values('t_known').groupby('trip').tail(5).index)
    events = events[(events.t_known.dt.as_unit('ns').astype('int64') >= retained_from) | events.index.isin(old_recent_five)]
    research_events = []
    for row in events.itertuples():
        if row.trip not in trip_first:
            raise ValueError('Event trip absent from source prefix')
        research_events.append(dict(vehicle_id=str(trip_vehicle[row.trip]), trip_id=int(trip_first[row.trip]),
            stop_order=int(row.stop), arrive_at=iso(row.t_arrive), known_at=iso(row.t_known),
            arrive=int(row.arrive), depart=int(row.depart)))
    x50 = np.load(rebuild / f'X50_{args.route}_s19.npy', mmap_mode='r')
    cases = []
    for sample, origin in selected:
        local = pd.Timestamp(sample.t_pred, tz='UTC').tz_convert('Asia/Seoul')
        sample['minute'] = local.hour * 60 + local.minute
        sample['weekday'] = local.weekday()
        band = 'morning' if 360 <= sample.minute < 600 else 'evening'
        means = bands.get((int(sample.target), band), [np.nan] * 3)
        x = x50[int(sample.sample_row)]
        seat = np.r_[x, sample[SEAT_COLUMNS].to_numpy(dtype='float32'), np.asarray(means, dtype='float32'),
                     sample[['entry_rank', 'entry_via_run', 'island', 'via_between']].to_numpy(dtype='float32')].astype('float32')
        time = [float(a <= sample.hour < b) for a, b in TIME_BINS] + [sample.weekend,
            sample.weekend * (7 <= sample.hour < 9), sample.weekend * (17 <= sample.hour < 20)]
        xtime = np.r_[x, sample[OBS + EST + PATH + TODAY].to_numpy(dtype='float32'), np.asarray(time, dtype='float32')].astype('float32')
        pool = np.r_[xtime.astype('float64'), int(sample.h)]
        cases.append(dict(sample_row=int(sample.sample_row), source_id=origin, target=int(sample.target), seat88=seat, probability80=pool))
    manifest = json.loads((args.bundle / 'statistics-manifest.json').read_text())
    payload = dict(schema='les-source-feature-fixture-v1', route=args.route, day=args.day, statistics=statistics,
        holidays=manifest.get('holidays', []), observations=observations, prefix=prefix, research_events=research_events, cases=cases,
        ambiguous_source_rows_excluded=ambiguous,
        original_sample_sha256=sha256(rebuild / f'samples_{args.route}_s19.pkl'))
    args.output.write_text(json.dumps(clean(payload), ensure_ascii=False, separators=(',', ':')))
    print(json.dumps(dict(path=str(args.output), observations=len(observations), events=len(research_events), cases=len(cases), bytes=args.output.stat().st_size)))


if __name__ == '__main__':
    main()
