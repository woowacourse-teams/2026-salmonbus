"""Export frozen feature statistics using hash-pinned AlightingAware source and local inputs."""
import ast
import json
from pathlib import Path

import numpy as np
import pandas as pd

from contracts import ROUTES, TARGET_STATS, sha256, verify_sources

QUEUE_HASH = '806b922ea4a8cd4c25b038c0bc5ca8b171177d8c9562176fc060a58bb8e30324'


def original_stat_functions(handoff):
    verify_sources(handoff)
    path = handoff / 'recover/analysis/queue_state.py'
    if sha256(path) != QUEUE_HASH:
        raise ValueError('Original queue statistics source changed')
    # This reviewed module contains imports/constants/functions only; no data I/O.
    scope = {}
    exec(compile(path.read_text(), str(path), 'exec'), scope)
    source = ast.parse((handoff / 'recover/analysis/samples3.py').read_text())
    route_loop = next(n for n in source.body if isinstance(n, ast.For))
    def assigns(node, name):
        return isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets)
    start = next(i for i, n in enumerate(route_loop.body) if assigns(n, 'days'))
    end = next(i for i, n in enumerate(route_loop.body) if assigns(n, 'min_per_stop'))
    # Execute exactly the original static-table block, not the costly sample builder.
    block = ast.Module(body=route_loop.body[start:end + 1], type_ignores=[])
    return scope, compile(block, 'samples3.py:static-statistics', 'exec')


def entries(table):
    return [dict(key=list(key), value=float(value)) for key, value in sorted(table.items())]


def verify_sample_statistics(samples, scope):
    positions = np.unique(np.linspace(0, len(samples) - 1, min(8192, len(samples)), dtype=int))
    errors = dict(target_full=0., target_departure=0., path_net=0., queue_unknown=0., queue_mine=0.)
    for row in samples.iloc[positions].itertuples():
        dtype = 'off' if row.weekend else 'work'
        minute = pd.Timestamp(row.t_pred, unit='ns', tz='UTC').tz_convert('Asia/Seoul').hour * 60
        minute += pd.Timestamp(row.t_pred, unit='ns', tz='UTC').tz_convert('Asia/Seoul').minute
        key = (row.target, dtype, minute // 30)
        comparisons = dict(target_full=(row.p_target_full_rate, scope['full_tab'].get(key, 0.)),
                           target_departure=(row.p_target_mean_dep, scope['dep_tab'].get(key, 0.) / 70),
                           path_net=(row.p_path_net, sum(scope['net_tab'].get((s, dtype, minute // 30), 0.)
                                     for s in range(row.target - row.h + 1, row.target + 1)) / row.capacity))
        rate = scope['lam_at'](scope['lam'], scope['lam_base'], row.target, dtype, minute)
        if row.q_unknown:
            comparisons['queue_unknown'] = (row.q_left, rate * 10 / row.capacity)
        # Source divided an integer seat sum by float32 capacity, losing precision.
        # Recover that integer before recomputing; no tolerance relaxation.
        if row.q_ahead_seats < 3:
            comparisons['queue_mine'] = (row.q_left_mine,
                max(0., row.q_left * row.capacity + rate * row.h * scope['min_per_stop']
                    - round(row.q_ahead_seats * row.capacity)) / row.capacity)
        for name, (actual, expected) in comparisons.items():
            error = abs(actual - expected)
            if not np.isfinite(error) or error > 1e-10:
                raise ValueError(f'Statistics no longer match samples: {name} {error}')
            errors[name] = max(errors[name], float(error))
    return dict(rows=len(positions), max_absolute_difference=errors, tolerance=1e-10)


def export_statistics(run, handoff, destination):
    scope_base, block = original_stat_functions(handoff)
    lab = run / 'lab'
    rebuild = lab / 'data/rebuild'
    derived = lab / 'data/derived'
    metadata = json.loads((rebuild / 'routes.json').read_text())
    bench = pd.read_pickle(derived / 'bench_frame.pkl')
    source_hashes = {'data/rebuild/routes.json': sha256(rebuild / 'routes.json')}
    report = {}
    routes = []
    for route in ROUTES:
        key = metadata[route]['key']
        paths = {name: rebuild / f'{name}_{key}.pkl' for name in ['batches', 'events', 'obs']}
        # Reproduction stores observation trips under this name.
        paths['obs'] = rebuild / f'obs_{key}_trips.pkl'
        paths['samples'] = rebuild / f'samples_{route}_s19.pkl'
        paths['labels'] = derived / f'ylabel_{route}.pkl'
        paths['alighting'] = derived / f'alight_fit_{route}.json'
        for name, path in paths.items():
            source_hashes[str(path.relative_to(lab))] = sha256(path)
        batches = pd.read_pickle(paths['batches']).dropna(subset=['received'])
        obs = pd.read_pickle(paths['obs'])
        obs = obs[obs.batch.isin(batches.batch)][['received']]
        ev = pd.read_pickle(paths['events'])
        scope = dict(scope_base, o=obs, ev=ev, TABLE_END=pd.Timestamp('2026-10-19').date())
        exec(block, scope)
        del obs, batches
        samples = pd.read_pickle(paths['samples'])
        sample_check = verify_sample_statistics(samples, scope)
        labels = pd.read_pickle(paths['labels'])
        # Exactly scoreboard's mean over ALL pre-10/12 rows, before 30% keep sampling.
        frame = samples.iloc[labels.row.values][['target', 'date']].reset_index(drop=True)
        frame['band'] = labels.band.values
        frame['t_alight_mean'] = labels.post_alight.values
        frame['t_after_mean'] = labels.after.values
        frame['t_arrive_mean'] = labels.arrive.values
        means = frame[frame.date < pd.Timestamp('2026-10-12').date()].groupby(['target', 'band'])[TARGET_STATS].mean()
        local = bench[bench.route == route]
        join_keys = pd.MultiIndex.from_arrays([samples.target.values[local.sample_row.values], local.band.values],
                                             names=['target', 'band'])
        expected = means.reindex(join_keys).values
        actual = local[TARGET_STATS].values
        if not np.array_equal(np.isnan(expected), np.isnan(actual)) or not np.allclose(expected, actual, atol=1e-12, rtol=0, equal_nan=True):
            raise ValueError(f'Target averages differ from training: {route}')
        target_error = float(np.nanmax(np.abs(expected - actual)))
        tables = {name: entries(scope[name]) for name in ['lam', 'lam_base', 'net_tab', 'full_tab', 'dep_tab']}
        tables['stop_net_base'] = entries(scope['stop_lvl'].net.to_dict())
        tables['target_band'] = [dict(stop=int(stop), band=band, values=row.tolist())
                                 for (stop, band), row in means.iterrows()]
        payload = dict(route=route, route_metadata=metadata[route]['stops'],
                       events_known_through=str(ev.t_known.max()),
                       observations_through=str(scope['o'].received.max()),
                       min_per_stop=scope['min_per_stop'], tables=tables,
                       alighting=json.loads(paths['alighting'].read_text()))
        (destination / f'statistics-{route}.json').write_text(json.dumps(payload, allow_nan=False, separators=(',', ':')) + '\n')
        report[route] = dict(sample_check=sample_check, target_average_rows=len(local),
                             target_average_max_difference=target_error, target_groups=len(means))
        routes.append(route)
        print(f'Statistics verified: {route}, {len(local)} target-average rows', flush=True)
        del scope, ev, samples, frame, labels, local
    manifest = dict(schema='alighting-statistics-v1', routes=routes, source_hashes=source_hashes,
                    static_table_end_exclusive='2026-10-19', target_mean_train_end_exclusive='2026-10-12',
                    travel_time_scope='all source events (original recipe)',
                    holiday_dates=sorted(str(d) for d in scope_base['HOLIDAYS']),
                    queue_source_sha256=QUEUE_HASH,
                    time_policy='preserve Asia/Seoul source timezone and original bin rules',
                    defaults=dict(lam='cell -> stop/daytype -> 0', path_net=0, target_full=0,
                                  target_departure=0, today_net='cell -> stop/daytype -> 0', target_means='NaN'),
                    verification=report)
    (destination / 'statistics-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest
