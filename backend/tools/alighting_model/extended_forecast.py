"""Evaluate an off-peak seat forecast on trusted local closure-confirmed inputs."""

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from threadpoolctl import threadpool_limits

from causal_prepare import original_alighting
from contracts import sha256
from extended_calibration import classify, eligible_events
from export_bundle import export_tree_model

FEATURES = ['seats', 'capacity', 'stop', 'horizon', 'minute', 'off_day']
PERIODS = {
    'train': ('2026-10-22', '2026-10-29'),
    'validation': ('2026-10-29', '2026-10-31'),
    'holdout': ('2026-10-31', '2026-11-05'),
}


def estimated_targets(events, calibration, functions):
    cells, _ = classify(events)
    labels = []
    functions['BIN_MINUTES'] = 180
    functions['BANDS']['extended'] = (0, 1440)
    for name, fit in calibration.items():
        if fit['status'] != 'PILOT_ONLY':
            continue
        rows = events.loc[cells.eq(name)]
        for offset in range(0, len(rows), 1000):
            batch = rows.iloc[offset:offset + 1000].copy()
            data = functions['prepare'](batch, 'extended')
            weights, likelihood, _, _ = functions['components'](np.asarray(fit['theta']), data)
            posterior = weights * likelihood
            mass = posterior.sum(axis=1)
            valid = mass > 0
            expected = (posterior @ np.arange(posterior.shape[1]))[valid] / mass[valid]
            batch = batch.loc[valid].copy()
            batch['label'] = batch.arrive + expected
            labels.append(batch)
    if not labels:
        return events.iloc[:0].assign(label=pd.Series(dtype='float64'))
    return pd.concat(labels, ignore_index=True)


def join_origins(targets, observations):
    origins = observations[['observation_id', 'trip', 'chain', 'passed', 'received', 'seats', 'capacity']]
    origins = origins.loc[origins.capacity.between(30, 70) & origins.seats.ge(0)
                          & origins.seats.le(origins.capacity)]
    keys = {key: 'int64' for key in ('trip', 'chain', 'passed')}
    origins = origins.astype(keys).sort_values(['received', 'observation_id'])
    targets = targets.sort_values('t_arrive')
    samples = []
    for horizon in range(1, 13):
        rows = targets.assign(passed=targets.stop - horizon, horizon=horizon).astype(keys)
        joined = pd.merge_asof(rows, origins, left_on='t_arrive', right_on='received',
                               by=['trip', 'chain', 'passed'], allow_exact_matches=False,
                               tolerance=pd.Timedelta(minutes=60), suffixes=('_target', ''))
        samples.append(joined.dropna(subset=['observation_id']))
    return pd.concat(samples, ignore_index=True)


def origin_features(targets, observations):
    frame = join_origins(targets, observations)
    local = frame.received.dt.tz_convert('Asia/Seoul')
    origin_cells, _ = classify(frame.assign(t_depart=frame.received))
    frame = frame.loc[~origin_cells.isin(['work-morning', 'work-evening'])].copy()
    frame['cell'] = origin_cells
    frame['minute'] = local.dt.hour * 60 + local.dt.minute
    frame['off_day'] = ~frame.cell.str.startswith('work-')
    return frame


def departure_partition(observations, events, period):
    start, end = [pd.Timestamp(day, tz='Asia/Seoul') for day in period]
    # Departure zero is directly observed, so no headway or posterior label is needed.
    targets = events.loc[events.t_arrive.ge(start) & events.t_known.lt(end) & events.depart.notna(),
                         ['trip', 'chain', 'stop', 't_arrive', 'depart', 'closure_id']]
    origins = observations.loc[observations.received.ge(start) & observations.received.lt(end)]
    return origin_features(targets, origins)


def forecast_partitions(observations, events, calibration, functions):
    partitions = {}
    for name, (start, end) in PERIODS.items():
        start, end = [pd.Timestamp(day, tz='Asia/Seoul') for day in (start, end)]
        targets = events.loc[events.t_arrive.ge(start) & eligible_events(events, end)]
        labels = estimated_targets(targets, calibration, functions)
        origins = observations.loc[observations.received.ge(start) & observations.received.lt(end)]
        partitions[name] = origin_features(labels, origins)
    return partitions


def evaluate(observations, events, calibration, functions):
    partitions = forecast_partitions(observations, events, calibration, functions)
    train = partitions['train']
    if train.empty:
        return dict(status='INSUFFICIENT_TRAINING_ROWS', train_rows=0)
    model = HistGradientBoostingRegressor(loss='absolute_error', max_iter=70, max_leaf_nodes=15,
                                          min_samples_leaf=100, early_stopping=False, random_state=0)
    model.fit(train[FEATURES], train.label)
    full_labels = train.depart.eq(0)
    full_rate = float(full_labels.mean())
    full = None
    if full_labels.nunique() == 2:
        full = HistGradientBoostingClassifier(max_iter=70, max_leaf_nodes=15, min_samples_leaf=100,
                                              early_stopping=False, random_state=0)
        full.fit(train[FEATURES], full_labels)
    result = dict(train_rows=len(train), train_departure_full_rate=full_rate,
                  train_unique_full_visits=int(train.loc[full_labels, 'closure_id'].nunique()))
    for name in ('validation', 'holdout'):
        frame = partitions[name].copy()
        if frame.empty:
            result[name] = {}
            continue
        prediction = np.clip(model.predict(frame[FEATURES]), 0, frame.capacity)
        frame['error'] = (frame.label - prediction).abs()
        frame['baseline_error'] = (frame.label - frame.seats).abs()
        actual_full = frame.depart.eq(0)
        frame['full_brier'] = np.nan
        if full is not None:
            frame['full_brier'] = (full.predict_proba(frame[FEATURES])[:, 1] - actual_full) ** 2
        frame['baseline_brier'] = (full_rate - actual_full) ** 2
        result[name] = {
            cell: dict(rows=len(rows), unique_target_visits=rows.closure_id.nunique(), unique_full_visits=rows.loc[rows.depart.eq(0), 'closure_id'].nunique(),
                       candidate_mae=rows.error.mean(), current_seats_mae=rows.baseline_error.mean(),
                       departure_full_brier=None if full is None else rows.full_brier.mean(),
                       constant_rate_brier=rows.baseline_brier.mean())
            for cell, rows in frame.groupby('cell', sort=True)
        }
    return result


def export_models(source, calibration, lab, output):
    if output.exists():
        raise ValueError('Extended output already exists')
    names = ('work-day', 'work-night', 'weekend-day', 'weekend-night')
    pooled_theta = {}
    for name in names:
        eligible = [route['cells'][name] for route in calibration['routes'].values()
                    if route['cells'].get(name, {}).get('status') == 'PILOT_ONLY']
        pooled_theta[name] = np.average([cell['theta'] for cell in eligible], axis=0,
                                        weights=[cell['fit_rows'] for cell in eligible]).tolist()

    routes = {}
    classifier_rows = []
    test_rows = []
    probability_test_rows = []
    for route, receipt in calibration['routes'].items():
        events_path = source / f'events_{route}.pkl'
        if sha256(events_path) != receipt['source_sha256']:
            raise ValueError(f'Calibration event hash does not match for {route}')
        cells = {name: receipt['cells'].get(name, {}) for name in names}
        theta = {name: cell['theta'] if cell.get('status') == 'PILOT_ONLY' else pooled_theta[name]
                 for name, cell in cells.items()}
        theta.update({'holiday-day': theta['weekend-day'], 'holiday-night': theta['weekend-night']})
        fit = {name: dict(status='PILOT_ONLY', theta=value) for name, value in theta.items()}
        obs = pd.read_pickle(source / f'obs_{route}_trips.pkl')
        events = pd.read_pickle(events_path)
        partitions = forecast_partitions(obs, events, fit, original_alighting(lab))
        train = partitions['train']
        regressor = HistGradientBoostingRegressor(loss='absolute_error', max_iter=70, max_leaf_nodes=15,
                                                  min_samples_leaf=100, early_stopping=False, random_state=0)
        regressor.fit(train[FEATURES], train.label)
        probability_train = departure_partition(obs, events, PERIODS['train'])
        stride = max(1, (len(probability_train) + 49_999) // 50_000)
        classifier_rows.append(probability_train.iloc[::stride][FEATURES + ['depart']].copy())
        del probability_train
        probability_test = departure_partition(obs, events, PERIODS['holdout'])
        probability_test = probability_test[FEATURES + ['cell', 'depart', 'closure_id']].copy()
        probability_test['route'] = route
        probability_test_rows.append(probability_test)
        holdout = partitions['holdout']
        prediction = np.clip(regressor.predict(holdout[FEATURES]), 0, holdout.capacity)
        holdout = holdout[FEATURES + ['cell', 'depart', 'closure_id', 'label']].copy()
        holdout['route'] = route
        holdout['candidate_mae'] = np.abs(prediction - holdout.label)
        holdout['current_seats_mae'] = np.abs(holdout.seats - holdout.label)
        test_rows.append(holdout)
        routes[route] = dict(seats=export_tree_model(regressor, 'regression'),
                             settlement={name: dict(lam=np.exp(value[:8]).tolist(),
                                                   p=float(1 / (1 + np.exp(-value[8]))),
                                                   kappa=float(np.exp(value[9])), r=float(np.exp(value[10])))
                                         for name, value in theta.items()})

    train = pd.concat(classifier_rows, ignore_index=True)
    full = HistGradientBoostingClassifier(max_iter=70, max_leaf_nodes=15, min_samples_leaf=500,
                                          early_stopping=False, random_state=0)
    labels = train.depart.eq(0).astype(int)
    full.fit(train[FEATURES], labels)
    holdout = pd.concat(test_rows, ignore_index=True)
    probability = pd.concat(probability_test_rows, ignore_index=True)
    truth = probability.depart.eq(0)
    probability['departure_full_brier'] = (full.predict_proba(probability[FEATURES])[:, 1] - truth) ** 2
    probability['constant_rate_brier'] = (labels.mean() - truth) ** 2
    report = []
    probabilities = probability.groupby(['route', 'cell'])
    for (route, cell), rows in holdout.groupby(['route', 'cell']):
        entry = dict(route=route, cell=cell, rows=len(rows), **rows[
            ['candidate_mae', 'current_seats_mae']].mean().to_dict())
        if (route, cell) in probabilities.groups:
            cases = probabilities.get_group((route, cell))
            entry.update(probability_rows=len(cases), probability_visits=cases.closure_id.nunique(), **cases[
                ['departure_full_brier', 'constant_rate_brier']].mean().to_dict())
        report.append(entry)
    output.write_text(json.dumps(dict(schema='alighting-extended-six-v1', features=FEATURES, routes=routes,
                                      full=export_tree_model(full, 'binary')), separators=(',', ':')))
    return dict(pooled_training_rows=len(train), holdout=report, artifact_sha256=sha256(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--calibration', type=Path, required=True)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--export', type=Path)
    args = parser.parse_args()
    calibration = json.loads(args.calibration.read_text())
    if sha256(args.lab) != calibration['lab_sha256']:
        parser.error('Calibration lab hash does not match')
    if calibration['periods']['fit'][1] > PERIODS['train'][0]:
        parser.error('Calibration fit overlaps forecast training')
    report = {}
    with threadpool_limits(limits=1):
        if args.export:
            report = export_models(args.source, calibration, args.lab, args.export)
            print(json.dumps(dict(periods=PERIODS, calibration_sha256=sha256(args.calibration), **report), indent=2))
            return
        for route, receipt in calibration['routes'].items():
            events = args.source / f'events_{route}.pkl'
            observations = args.source / f'obs_{route}_trips.pkl'
            if sha256(events) != receipt['source_sha256']:
                parser.error(f'Calibration event hash does not match for {route}')
            report[route] = evaluate(pd.read_pickle(observations), pd.read_pickle(events),
                                     receipt['cells'], original_alighting(args.lab))
            report[route]['observations_sha256'] = sha256(observations)
    print(json.dumps(dict(periods=PERIODS, calibration_sha256=sha256(args.calibration), routes=report), indent=2))


if __name__ == '__main__':
    main()
