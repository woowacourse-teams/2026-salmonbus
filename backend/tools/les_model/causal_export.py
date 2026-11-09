"""Export the new trees and genuinely pre-training frozen statistics."""
import argparse
import ast
import json
from pathlib import Path
import shutil

import joblib
import numpy as np
import pandas as pd
from scipy.special import expit
from threadpoolctl import threadpool_limits

from causal_prepare import FREEZE
from contracts import sha256, gate_base
from export_bundle import export_tree_model, boundary_cases
from export_statistics import entries, verify_sample_statistics, QUEUE_HASH


def export(source, generated, model_dir, lab, parent, output):
    if output.exists():
        raise ValueError('Bundle exists')
    output.mkdir(parents=True)
    frame = pd.read_pickle(model_dir / 'frame.pkl')
    seat = np.load(model_dir / 'seat88.npy', mmap_mode='r')
    prob = np.load(model_dir / 'probability80.npy', mmap_mode='r')
    expert, gate, pool = [joblib.load(model_dir / n) for n in ['expert.joblib', 'gate.joblib', 'pool.joblib']]
    selection = json.loads((model_dir / 'selection.json').read_text())
    training_report = json.loads((model_dir / 'report.json').read_text())
    freeze = pd.Timestamp(training_report.get('freeze', str(FREEZE)))
    final_start, final_end = training_report.get('periods', {}).get('final', ['2026-10-22', '2026-10-27'])
    middle = selection['middle']
    # Stratify only by route and time; never select favorable error cases.
    rows = []
    final_cutoff = pd.Timestamp(final_end, tz='Asia/Seoul') + pd.Timedelta(days=1)
    final_known = pd.to_datetime(frame.label_known, utc=True) < final_cutoff
    for route in sorted(frame.route.unique()):
        positions = np.flatnonzero((frame.route == route)
                                   & frame.date.astype(str).between(final_start, final_end) & final_known)
        if not len(positions):
            raise ValueError(f'No final-period reference rows: {route}')
        rows.extend(positions[np.linspace(0, len(positions)-1, min(128, len(positions)), dtype=int)])
    sx, px = seat[rows], prob[rows]
    with threadpool_limits(limits=1):
        b = np.maximum(expert.predict(sx), 0)
        p = gate.predict_proba(sx)
        full = pool.predict_proba(px)[:, 1]
    values = lambda a: np.where(np.isnan(a), None, a).tolist()
    golden = dict(schema='les-reference-cases-v1', case_type='real-causal-inputs',
                  seat_inputs=values(sx), probability_inputs=values(px), expected_base=b.tolist(),
                  expected_gate_probabilities=p.tolist(), expected_seats=gate_base(b, p, middle).tolist(),
                  expected_departure_full=full.tolist(), middle=middle)
    for name, obj in [('golden.json', golden), ('boundaries.json', boundary_cases(expert, gate, pool, golden)),
                      ('constants.json', dict(middle=middle, classes=[0, 1, 2], target='after'))]:
        (output / name).write_text(json.dumps(obj, allow_nan=False))
    for name, model, kind in [('base', expert, 'regression'), ('gate', gate, 'multiclass'), ('pool', pool, 'binary')]:
        (output / f'{name}.json').write_text(json.dumps(export_tree_model(model, kind), allow_nan=False))
    for name in ['feature-contract.json', 'route-reference.json']:
        shutil.copyfile(parent / name, output / name)
    metadata = json.loads((source / 'routes.json').read_text())
    route_reference = json.loads((output / 'route-reference.json').read_text())
    route_reference['routes'] = [dict(modelRoute=name, sourceRouteId=meta['public_route_id'],
                                      stops=meta['stops']) for name, meta in metadata.items()]
    (output / 'route-reference.json').write_text(json.dumps(route_reference, ensure_ascii=False, indent=2))
    contract = json.loads((output / 'feature-contract.json').read_text())
    contract.update(source_reference=f'causal: declared confirmation time, closure-witness history, tables frozen before {freeze}',
                    online_feature_parity_verified=False)
    (output / 'feature-contract.json').write_text(json.dumps(contract, indent=2))
    queue_path = lab / 'recover/analysis/queue_state.py'
    if sha256(queue_path) != QUEUE_HASH:
        raise ValueError('Queue source changed')
    scope_base = {}
    exec(compile(queue_path.read_text(), str(queue_path), 'exec'), scope_base)
    tree = ast.parse((generated / 'samples_causal.py').read_text())
    loop = next(n for n in tree.body if isinstance(n, ast.For))
    def assignment(n, key):
        return isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name) and n.targets[0].id == key
    start = next(i for i,n in enumerate(loop.body) if assignment(n,'days'))
    end = next(i for i,n in enumerate(loop.body) if assignment(n,'min_per_stop'))
    block = compile(ast.Module(body=loop.body[start:end+1], type_ignores=[]), 'frozen-statistics', 'exec')
    metadata = json.loads((source / 'routes.json').read_text())
    checks = {}
    for route, meta in metadata.items():
        ev = pd.read_pickle(source / f'events_{route}.pkl')
        ev = ev[ev.t_known < freeze]
        obs = pd.read_pickle(source / f'obs_{route}_trips.pkl')[['received']]
        obs = obs[obs.received < freeze]
        scope = dict(scope_base, o=obs, ev=ev, TABLE_END=freeze.date())
        exec(block, scope)
        samples = pd.read_pickle(source / f'samples_{route}_causal.pkl')
        checks[route] = verify_sample_statistics(samples, scope)
        tables = {n:entries(scope[n]) for n in ['lam','lam_base','net_tab','full_tab','dep_tab']}
        tables['stop_net_base'] = entries(scope['stop_lvl'].net.to_dict())
        means = json.loads((model_dir / f'target-means-{route}.json').read_text())
        means_by_key = {(r['stop'],r['band']):[r['t_alight_mean'],r['t_after_mean'],r['t_arrive_mean']]
                        for r in means}
        local = frame[frame.route == route]
        expected = np.asarray([means_by_key.get((t,b),[np.nan]*3)
                               for t,b in zip(local.target,local.band)],dtype='float32')
        actual = local[['t_alight_mean','t_after_mean','t_arrive_mean']].values.astype('float32')
        if not ((actual == expected) | (np.isnan(actual) & np.isnan(expected))).all():
            raise ValueError('Exported target means differ from model input precision')
        tables['target_band'] = [dict(stop=int(r['stop']), band=r['band'],
                                     values=[r['t_alight_mean'],r['t_after_mean'],r['t_arrive_mean']]) for r in means]
        parameters = json.loads((source / f'alight_parameters_{route}.json').read_text())
        rows = []
        for s in meta['stops']:
            if not s['boardingAllowed']:
                continue
            for param in parameters:
                theta = np.asarray(param['theta'])
                rows.append(dict(stop=s['order'],band=param['band'],lam=np.exp(theta[:8]).tolist(),
                                 p=float(expit(theta[8])),kappa=float(np.exp(theta[9])),r=float(np.exp(theta[10]))))
        payload = dict(route=route, route_metadata=meta['stops'], tables=tables,
                       min_per_stop=scope['min_per_stop'], events_known_through=str(ev.t_known.max()),
                       observations_through=str(obs.received.max()),
                       alighting=dict(route=route,grouping='route/band pooled',stops=rows))
        (output / f'statistics-{route}.json').write_text(json.dumps(payload, allow_nan=False))
        print('frozen statistics verified',route,flush=True)
    stats_manifest = dict(schema='les-statistics-v1', routes=list(metadata),
                          static_table_end_exclusive=str(freeze.date()),target_mean_train_end_exclusive=str(freeze.date()),
                          holiday_dates=sorted(str(d) for d in scope_base['HOLIDAYS']),
                          verification=checks, travel_time_scope='closed before training freeze')
    (output / 'statistics-manifest.json').write_text(json.dumps(stats_manifest, indent=2))
    for original,name in [(source/'source-receipt.json','source-receipt.json'),
                          (source/'calibration-receipt.json','calibration-receipt.json'),
                          (model_dir/'report.json','training-report.json'),
                          (generated/'sample-source-receipt.json','sample-source-receipt.json')]:
        shutil.copyfile(original,output/name)
    manifest = dict(schema='les-inference-bundle-v1',model='les-gate-base-and-pool',serving_ready=False,
                    output_targets=['after-alighting-estimated','departure-zero'],
                    training_variant='causal-les-donkey-routeband-v1',
                    trained_model_hashes={n:sha256(model_dir/n) for n in ['expert.joblib','gate.joblib','pool.joblib']},
                    files={p.name:sha256(p) for p in sorted(output.iterdir())},
                    pending=['online Java feature parity','actual-time Worker replay','activation receipts'])
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print(json.dumps(dict(bundle=str(output),manifest_sha256=sha256(output/'manifest.json'))),flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['source','generated','model-dir','lab','parent','output']:
        parser.add_argument('--'+name,type=Path,required=True)
    a=parser.parse_args()
    export(a.source,a.generated,a.model_dir,a.lab,a.parent,a.output)
