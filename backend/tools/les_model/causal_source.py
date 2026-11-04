"""Prepare versioned LIVE observations using declared input-confirmation time.

No forecasts or evaluation truths are read. This is a source adapter, not proof
of database commit time, online feature parity, or model accuracy.
"""
import argparse
import gc
import json
from pathlib import Path

import numpy as np
import pandas as pd

from contracts import ROUTES, sha256


def available_time(received, confirmed):
    """Missing/conflicting confirmation is rejected, never backdated."""
    return confirmed.where(received.notna() & confirmed.notna() & (confirmed >= received))


def prepare(source, output):
    if output.exists():
        raise ValueError('Output exists; preserve source receipts')
    output.mkdir(parents=True)
    parts = output / 'parts'
    parts.mkdir()
    route = pd.read_csv(source / 'route.csv.gz', dtype={'display_name': str, 'public_route_id': str})
    versions = pd.read_csv(source / 'route_version.csv.gz').merge(
        route, left_on='route_id', right_on='id', suffixes=('', '_route'))
    selected = versions[versions.display_name.isin(ROUTES)]
    names = dict(zip(selected.id, selected.display_name))
    stops = pd.read_csv(source / 'route_stop.csv.gz', dtype={'stop_id': str})
    metadata = {}
    for name in ROUTES:
        vs = selected[selected.display_name == name]
        if vs.turn_sequence.nunique() != 1:
            raise ValueError(f'Turn sequence changed across route versions: {name}')
        catalogs = []
        for version in vs.id:
            st = stops[stops.route_version_id == version].sort_values('stop_order')
            catalogs.append(st[['stop_order', 'stop_id', 'direction', 'boarding_allowed']].to_json(orient='records'))
        if len(set(catalogs)) != 1:
            raise ValueError(f'Catalogs differ; separate model inputs required: {name}')
        st = stops[stops.route_version_id == int(vs.id.max())].sort_values('stop_order')
        metadata[name] = dict(key=name, public_route_id=str(vs.public_route_id.iloc[0]),
                              versions=[int(v) for v in vs.id],
                              turn=int(vs.turn_sequence.iloc[0]),
                              stops=[dict(order=int(s.stop_order), id=str(s.stop_id), name=s['name'],
                                          direction=s.direction, boardingAllowed=s.boarding_allowed == 't')
                                     for _, s in st.iterrows()])
    receipts = {}
    batches = []
    audit = dict(origin_policy='LIVE only', availability='input_confirmed_at >= response_received_at',
                 max_latency_seconds=90, batch_rows=0, eligible_batches=0, observation_rows=0,
                 eligible_observations=0, source_hashes={})
    manifest = {r['file']: r['sha256'] for r in
                (json.loads(line) for line in (source.parent / 'manifest.jsonl').read_text().splitlines() if line.strip())}
    def pin_source(path):
        actual = sha256(path)
        if actual != manifest.get(path.name):
            raise ValueError(f'Collection source digest changed: {path.name}')
        audit['source_hashes'][path.name] = actual
    columns = ['id', 'route_version_id', 'response_received_at', 'input_confirmed_at',
               'outcome', 'ingestion_origin', 'stored_rows']
    for path in sorted(source.glob('observation_batch_*.csv.gz')):
        pin_source(path)
        b = pd.read_csv(path, usecols=columns)
        audit['batch_rows'] += len(b)
        b = b[b.route_version_id.isin(names) & (b.ingestion_origin == 'LIVE')
              & (b.outcome == 'SUCCESS_ROWS') & (b.stored_rows > 0)].copy()
        received = pd.to_datetime(b.response_received_at, utc=True, format='ISO8601')
        confirmed = pd.to_datetime(b.input_confirmed_at, utc=True, format='ISO8601')
        b['received'] = available_time(received, confirmed)
        b['source_received'] = received
        b = b[b.received.notna() & ((b.received - received).dt.total_seconds() <= 90)]
        batches.append(b[['id', 'route_version_id', 'received', 'source_received']])
    batch = pd.concat(batches, ignore_index=True)
    if batch.id.duplicated().any():
        raise ValueError('Duplicate batch identity')
    audit['eligible_batches'] = len(batch)
    for name in ROUTES:
        b = batch[batch.route_version_id.map(names) == name].rename(columns={'id': 'batch', 'route_version_id': 'rv'})
        b.received = b.received.dt.tz_convert('Asia/Seoul')
        b.to_pickle(output / f'batches_{name}.pkl')
        receipts[name] = set(b.batch)
    cols = ['id', 'observation_batch_id', 'route_version_id', 'vehicle_id', 'stop_order',
            'running_state', 'passed_stop_order', 'remaining_seats', 'crowd_level', 'seat_unknown_reason']
    eligible_ids = set(batch.id)
    for index, path in enumerate(sorted(source.glob('vehicle_observation_*.csv.gz'))):
        pin_source(path)
        for chunk_id, o in enumerate(pd.read_csv(path, usecols=cols, chunksize=100000, dtype={'seat_unknown_reason': 'string'})):
            audit['observation_rows'] += len(o)
            o = o[o.observation_batch_id.isin(eligible_ids) & o.route_version_id.isin(names)].copy()
            o = o.rename(columns=dict(id='observation_id', observation_batch_id='batch', route_version_id='rv',
                                     vehicle_id='vid', running_state='state', passed_stop_order='passed',
                                     remaining_seats='seats', crowd_level='crowd', seat_unknown_reason='unknown_reason'))
            for name, g in o.groupby(o.rv.map(names), sort=False):
                if not g.batch.isin(receipts[name]).all():
                    raise ValueError('Observation/batch route mismatch')
                g.to_pickle(parts / f'{name}_{index:03}_{chunk_id:03}.pkl')
                audit['eligible_observations'] += len(g)
        if index % 10 == 0:
            print(json.dumps(dict(stage='source', files=index + 1, rows=audit['eligible_observations'])), flush=True)
    for name in ROUTES:
        files = sorted(parts.glob(f'{name}_*.pkl'))
        o = pd.concat([pd.read_pickle(p) for p in files], ignore_index=True)
        b = pd.read_pickle(output / f'batches_{name}.pkl')
        o = o.merge(b[['batch', 'received', 'source_received']], on='batch', validate='many_to_one')
        # Ambiguous duplicate source rows are removed in full, never first-row-wins.
        duplicate = o.duplicated(['batch', 'vid'], keep=False)
        audit[f'{name}_ambiguous_rows'] = int(duplicate.sum())
        o = o[~duplicate & o.vid.notna()].copy()
        o['vid'] = o.vid.astype('category')
        for col in ['stop_order', 'passed', 'state']:
            o[col] = o[col].astype('int16')
        for col in ['seats', 'crowd']:
            o[col] = o[col].astype('float32')
        o.loc[~o.seats.between(0, 70) | o.unknown_reason.notna(), 'seats'] = np.nan
        o.to_pickle(output / f'obs_{name}.pkl')
        metadata[name]['observations'] = len(o)
        for f in files:
            f.unlink()
        del o, b
        gc.collect()
    for name in ['route.csv.gz', 'route_version.csv.gz', 'route_stop.csv.gz']:
        pin_source(source / name)
    audit['verified_against_collection_manifest'] = True
    (output / 'routes.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
    (output / 'source-receipt.json').write_text(json.dumps(audit, indent=2))
    print(json.dumps(audit), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.source, args.output)
