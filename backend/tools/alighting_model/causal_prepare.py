"""Freeze AlightingAware statistics and alighting calibration before model training.

Research variant: route/band pooled alighting parameters bound calibration cost.
The posterior equation is AlightingAware's; this is not the original stop-specific fit.
"""
import argparse
import ast
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import pandas as pd
from scipy.optimize import minimize
from threadpoolctl import threadpool_limits

from contracts import sha256

FREEZE = pd.Timestamp('2026-10-12', tz='Asia/Seoul')
BANDS = {'morning': (360, 600), 'evening': (1020, 1260)}
HOLIDAYS = {'2026-08-17', '2026-09-24', '2026-09-25', '2026-10-05', '2026-10-09'}


def original_alighting(path):
    tree = ast.parse(path.read_text())
    nodes = [n for n in tree.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.FunctionDef))
             or (isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)
                 and n.targets[0].id in {'BANDS', 'BIN_MINUTES', 'N_BINS', 'FLOOR_SHARE'})]
    namespace = {}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), 'exec'), namespace)
    return namespace


def frozen_rows(events, freeze=FREEZE):
    return events[events.t_known.lt(freeze)].copy()


def calibrate(source, lab, freeze=FREEZE):
    functions = original_alighting(lab / 'scripts/alight_fit.py')
    metadata = json.loads((source / 'routes.json').read_text())
    receipts = dict(freeze_exclusive=str(freeze), grouping='route/band pooled', max_fit_rows=600,
                    optimizer='two fixed starts, L-BFGS-B, maxiter=100', groups=[])
    for route, meta in metadata.items():
        path = source / f'alight_post_{route}.pkl'
        # Existing completed route outputs allow an interrupted run to continue.
        if path.exists():
            raise ValueError('Calibration output exists; use a new version rather than silently reuse')
        events = pd.read_pickle(source / f'events_{route}.pkl')
        events = events[(events.weekday < 5) & ~events.date.astype(str).isin(HOLIDAYS)
                        & events.gap.between(0.2, 60) & (events.capacity >= 30)].copy()
        predictions, parameters = [], []
        for band, (lo, hi) in BANDS.items():
            group = events[events.minute.between(lo, hi - 1)]
            fit = frozen_rows(group, freeze).sort_values('t_known')
            if len(fit) < 80:
                raise ValueError(f'Insufficient pre-freeze calibration: {route}/{band}')
            fit = fit.iloc[np.linspace(0, len(fit) - 1, min(600, len(fit)), dtype=int)]
            data = functions['prepare'](fit, band)
            rate = max(float(np.maximum(data['arrive'] - data['depart'], 0).mean()) /
                       max(float(data['gap'].mean()), 1), 0.05)
            bounds = [(-8, 4)] * 8 + [(-9, 6), (-6, 4), (-4, 6)]
            trials = []
            for start_p in [-3., -1.]:
                initial = np.r_[np.full(8, np.log(rate)), start_p, 0., 1.]
                with threadpool_limits(limits=1):
                    trial = minimize(functions['negative_log_likelihood'], initial, args=(data,),
                                     method='L-BFGS-B', bounds=bounds, options={'maxiter': 100})
                if np.isfinite(trial.fun) and np.isfinite(trial.x).all():
                    trials.append(trial)
            if not trials:
                raise ValueError('No finite alighting fit')
            best = min(trials, key=lambda t: t.fun)
            item = dict(route=route, band=band, fit_rows=len(fit),
                        fit_max_known=str(fit.t_known.max()), converged=bool(best.success),
                        iterations=int(best.nit), theta=best.x.tolist(), objective=float(best.fun))
            parameters.append(item)
            receipts['groups'].append(item)
            # Applying a frozen label model to later outcomes does not feed those
            # outcomes into model inputs or update the frozen parameters.
            for offset in range(0, len(group), 1000):
                g = group.iloc[offset:offset + 1000].copy()
                pred = functions['predict'](best.x, functions['prepare'](g, band))
                g['band'] = band
                g['post_alight'] = pred['post_alight']
                g['post_alight_sd'] = pred['post_alight_sd']
                g['after'] = g.arrive + g.post_alight
                predictions.append(g)
            print(json.dumps({k: v for k, v in item.items() if k != 'theta'}), flush=True)
        pd.concat(predictions, ignore_index=True).to_pickle(path)
        (source / f'alight_parameters_{route}.json').write_text(json.dumps(parameters, indent=2))
    receipts['original_alight_source_sha256'] = sha256(lab / 'scripts/alight_fit.py')
    (source / 'calibration-receipt.json').write_text(json.dumps(receipts, indent=2))


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f'Original source contract changed: {old[:70]}')
    return text.replace(old, new)


def samples(source, lab, output, freeze=FREEZE, origin_every=8):
    if output.exists():
        raise ValueError('Sample output exists')
    original = lab / 'recover/analysis/samples3.py'
    text = original.read_text()
    bundle_node = next(n for n in ast.parse(text).body if isinstance(n, ast.Assign)
                       and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'BUNDLE')
    text = replace_once(text, ast.get_source_segment(text, bundle_node),
                        'BUNDLE = ' + repr(str((lab / 'data/bundle_coldstart20').resolve())))
    # Use the validated source catalog, including newly collected route versions.
    text = replace_once(text, "ref = {r['modelRoute']: r['stops'] for r in ref['routes']}",
                        "ref = {name: metadata['stops'] for name, metadata in META.items()}")
    text = replace_once(text, '    ev_sorted = ev.sort_values',
                        '    ev_sorted = ev[ev.t_known < pd.Timestamp(' + repr(str(freeze)) + ')].sort_values')
    text = replace_once(text, '    same = np.r_[False, vid_codes[1:] == vid_codes[:-1]]',
                        '    same = np.r_[False, (vid_codes[1:] == vid_codes[:-1]) & (o.chain.values[1:] == o.chain.values[:-1]) ]')
    text = replace_once(text, '    for i in np.flatnonzero(is_origin):',
                        '    is_origin &= (dates >= TABLE_END) & (dtypes == "work") & (((hours >= 6) & (hours < 10)) | ((hours >= 17) & (hours < 21))) & (maxseats >= 30)\n    for i in np.flatnonzero(is_origin):')
    text = replace_once(text, 'rows.append((route, dates[i]',
                        'rows.append((int(trips[i]), int(o.observation_id.values[i]), int(o.chain.values[i]), route, dates[i]')
    text = replace_once(text, "cols = ['route', 'date'", "cols = ['trip', 'origin_observation_id', 'origin_chain', 'route', 'date'")
    output.mkdir(parents=True)
    script = output / 'samples_causal.py'
    script.write_text(text)
    (output / 'sample-source-receipt.json').write_text(json.dumps(dict(
        original_sha256=sha256(original), generated_sha256=sha256(script), freeze=str(freeze),
        origin_every=origin_every, catalog='validated source routes.json',
        changes=['closure-confirmed history', 'frozen travel-time median', 'chain isolation',
                 'post-freeze weekday rush origins', 'origin identity preservation']), indent=2))
    (source / 'sample-contract.json').write_text((output / 'sample-source-receipt.json').read_text())
    meta = json.loads((source / 'routes.json').read_text())
    for route in meta:
        subprocess.run([sys.executable, str(script), str(source), str(lab / 'recover/analysis'),
                        str(origin_every), str(freeze.date()), '_causal', route], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['calibrate', 'samples'])
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--freeze', default=str(FREEZE.date()))
    parser.add_argument('--origin-every', type=int, default=8)
    args = parser.parse_args()
    freeze = pd.Timestamp(args.freeze, tz='Asia/Seoul')
    if args.origin_every < 1:
        parser.error('--origin-every must be positive')
    if args.stage == 'calibrate':
        calibrate(args.source, args.lab, freeze)
    else:
        samples(args.source, args.lab, args.output, freeze, args.origin_every)
