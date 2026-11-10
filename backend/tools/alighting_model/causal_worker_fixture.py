"""Export real confirmed-time replay cases with a source-derived midnight prefix.

The prefix is an explicit input to this bounded replay, not evidence that an
online bootstrap ran. No quality assignment from production is available.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from contracts import sha256

PROFILE = 'alighting-confirmed-closure-v2'


def instant(value):
    return value.tz_convert('UTC').isoformat().replace('+00:00', 'Z')


def row_json(row, turn):
    return dict(id=int(row.observation_id), batch_id=int(row.batch), batch_rank=int(row.batch_rank),
                received_at=instant(row.received), vehicle_id=str(row.vid),
                passed_stop_order=int(row.passed), stop_order=int(row.stop_order), running_state=int(row.state),
                remaining_seats=None if pd.isna(row.seats) else int(row.seats),
                crowd_level=0 if pd.isna(row.crowd) else int(row.crowd), route_version_id=int(row.rv),
                physical_received_at=instant(row.source_received),
                down=bool(row.stop_order > turn or (row.stop_order == turn and row.state == 2)
                          or (row.stop_order == 1 and row.state != 2)))


def generate(root, output, day, alighting_reference):
    if output.exists():
        raise ValueError('Preserve existing replay fixtures')
    output.mkdir(parents=True)
    source = root / 'source'
    from causal_prepare import original_alighting
    reference = original_alighting(alighting_reference)
    expected_reference = json.loads((source/'calibration-receipt.json').read_text())['original_alight_source_sha256']
    if sha256(alighting_reference) != expected_reference:
        raise ValueError('Use the exact independent alighting reference bound to the frozen parameters')
    frame = pd.read_pickle(root / 'model/frame.pkl')
    seat = np.load(root / 'model/seat88.npy', mmap_mode='r')
    probability = np.load(root / 'model/probability80.npy', mmap_mode='r')
    metadata = json.loads((source / 'routes.json').read_text())
    start = pd.Timestamp(day, tz='Asia/Seoul')
    end = start + pd.Timedelta(days=1)
    versions = pd.read_csv(root/'merged/data/route_version.csv.gz')
    ledgers = [pd.read_csv(p, usecols=['id', 'stored_rows']) for p in sorted((root/'merged/data').glob('observation_batch_*.csv.gz'))]
    raw_counts = pd.concat(ledgers).set_index('id').stored_rows
    receipts = []
    for route, meta in metadata.items():
        cases = frame[(frame.route == route) & (frame.date.astype(str) == day)]
        origins = cases.groupby('origin_observation_id', sort=False).t_pred.first().sort_values()
        picked = origins.iloc[np.linspace(0, len(origins) - 1, min(6, len(origins)), dtype=int)].index
        selected = []
        for identity in picked:
            g = cases[cases.origin_observation_id == identity].sort_values('h')
            selected.extend(g.iloc[np.unique([0, len(g) - 1])].index)
        observations = pd.read_pickle(source / f'obs_{route}_trips.pkl')
        batches = pd.read_pickle(source / f'batches_{route}.pkl').sort_values(['received', 'batch'])
        batches['rank'] = np.arange(len(batches))
        observations['batch_rank'] = observations.batch.map(batches.set_index('batch')['rank'])
        o = observations
        same = o.chain.eq(o.chain.shift()).values
        diff = o.passed.diff().fillna(0).values
        linked = same & (o['batch_rank'].diff().values == 1) & (diff >= 0) & (diff <= 1)
        zero = o.seats.eq(0).values
        continues = linked & zero & np.r_[False, zero[:-1]]
        increment = np.where(zero, np.where(continues, (diff != 0).astype(int), 1), 0)
        streak = pd.Series(increment).groupby(np.cumsum(~continues)).cumsum().values
        entry = np.maximum.accumulate(np.where(~same | (diff != 0), np.arange(len(o)), 0))
        trip_first = o.groupby('trip').observation_id.first().to_dict()
        prior = o[o.received < start].groupby('vid', observed=True).tail(1)
        previous_capacity = o.groupby('vid', observed=True).capacity.shift().fillna(0)
        capacity_updates = o[(o.received < start) & o.capacity.gt(previous_capacity)]
        witnesses = {r.vid: r for r in capacity_updates.groupby('vid', observed=True).tail(1).itertuples()}
        prefix = []
        for index, r in prior.iterrows():
            state = dict(capacity=float(r.capacity), full_streak=float(streak[index]),
                         previous=row_json(r, meta['turn']), entry_at=instant(o.iloc[entry[index]].received),
                         trip_id=int(trip_first[r.trip]))
            if r.vid in witnesses:
                state['capacity_observation'] = row_json(witnesses[r.vid], meta['turn'])
            prefix.append(state)
        window = o[o.received.between(start, end, inclusive='left')].sort_values(['received', 'batch', 'vid'])
        stats = json.loads((root / 'bundle' / f'statistics-{route}.json').read_text())
        stats['input_profile'] = PROFILE
        golden = []
        parameters = {p['band']: p for p in json.loads((source/f'alighting_parameters_{route}.json').read_text())}
        events = pd.read_pickle(source / f'events_{route}.pkl').copy()
        events['previous_post_id'] = events.groupby('stop').source_departure_id.shift()
        event_index = events.set_index(['trip', 'chain', 'stop'])
        for index in selected:
            case = frame.loc[index]
            event = event_index.loc[(case.trip, case.origin_chain, case.target)]
            capacity = int(seat[index, 53])
            if event.arrive > capacity or event.depart > capacity:
                raise ValueError('OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT: select and record a reviewed supported cohort')
            band = 'morning' if event.minute < 600 else 'evening'
            observed = event.to_frame().T.copy()
            observed['onboard'] = capacity - observed.arrive
            predicted = reference['predict'](np.asarray(parameters[band]['theta']), reference['prepare'](observed, band))
            frozen_after = float(event.arrive + predicted['post_alight'][0])
            golden.append(dict(source_id=int(case.origin_observation_id),
                               expected_pre=int(event.source_arrival_id), expected_post=int(event.source_departure_id),
                               expected_closure=int(event.closure_id), expected_capacity=capacity,
                               expected_previous_post=int(event.previous_post_id), target=int(case.target),
                               seat88=np.where(np.isnan(seat[index]), None, seat[index]).tolist(),
                               probability80=np.where(np.isnan(probability[index]), None, probability[index]).tolist(),
                               expected_after=frozen_after, expected_departure=int(case.true_depart),
                               expected_known=instant(case.label_known)))
        anchor = batches[batches.received < start].iloc[-1]
        route_versions = versions[versions.id.isin(meta['versions'])].copy()
        route_versions = json.loads(route_versions.to_json(orient='records'))
        counts = window.groupby('batch', observed=True).size()
        if not counts.eq(raw_counts.reindex(counts.index)).all():
            raise ValueError('Day source rows differ from raw stored_rows: '+route)
        needed_batches = set(window.batch) | {int(anchor.batch)}
        for state in prefix:
            needed_batches.add(state['previous']['batch_id'])
            if 'capacity_observation' in state:
                needed_batches.add(state['capacity_observation']['batch_id'])
        ledger = [dict(id=int(b.batch), rv=int(b.rv), confirmed_at=instant(b.received),
                       response_at=instant(b.source_received), stored_rows=int(raw_counts.loc[b.batch]))
                  for b in batches[batches.batch.isin(needed_batches)].itertuples()]
        payload = dict(capacity_policy="vehicle-capacity-snapshot-v1", label_version="alighting-confirmed-capacity-snapshot-v3", route=route, day=day, input_profile=PROFILE,
                       source_route_id=meta['public_route_id'], route_versions=route_versions, batches=ledger,
                       day_rows_verified_against_raw_ledger=True,
                       statistics=stats, holidays=json.loads((root/'bundle/statistics-manifest.json').read_text())['holiday_dates'],
                       observations=[row_json(r, meta['turn']) for r in window.itertuples()],
                       prefix=prefix, research_events=[], cases=golden,
                       anchor=dict(batch=int(anchor.batch), rank=int(anchor['rank']), time=instant(anchor.received),
                                   rv=int(anchor.rv)))
        path = output / f'{route}.json'
        path.write_text(json.dumps(payload, allow_nan=False))
        receipt = dict(route=route, cases=len(golden), observations=len(window), prefix_vehicles=len(prefix),
                       file=path.name, bytes=path.stat().st_size, sha256=sha256(path))
        receipts.append(receipt)
        print(json.dumps(receipt), flush=True)
    (output/'manifest.json').write_text(json.dumps(dict(profile=PROFILE, day=day, routes=receipts,
        capacity_policy="vehicle-capacity-snapshot-v1", label_version="alighting-confirmed-capacity-snapshot-v3",
        source_receipt_sha256=sha256(source/'source-receipt.json'),
        scope='source-derived midnight prefix plus all available day observations; quality state not exported'), indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--day', default='2026-11-04')
    p.add_argument('--alighting-reference', type=Path, required=True)
    a = p.parse_args()
    generate(a.root, a.output, a.day, a.alighting_reference)
