"""Fit and select Les/Donkey candidates without consulting final-period truths."""
import argparse
import importlib.util
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from threadpoolctl import threadpool_limits

from causal_prepare import FREEZE, frozen_rows
from contracts import (SEAT_COLUMNS, TARGET_STATS, ENTRY, OBS, EST, PATH, TODAY,
                       TIME_BINS, gate_base, sha256)
from optimize_combination import blend_experts, metrics


DEFAULT_PERIODS = {'train': ['2026-10-12', '2026-10-16'],
                   'dev': ['2026-10-19', '2026-10-21'],
                   'final': ['2026-10-22', '2026-10-27']}


def validate_periods(periods, freeze):
    if set(periods) != {'train', 'dev', 'final'}:
        raise ValueError('Exactly train, dev, final periods are required')
    previous_end = freeze - pd.Timedelta(days=1)
    for name in ['train', 'dev', 'final']:
        if len(periods[name]) != 2:
            raise ValueError('Each period requires inclusive start and end dates')
        start, end = [pd.Timestamp(day, tz='Asia/Seoul') for day in periods[name]]
        if start != start.normalize() or end != end.normalize() or start > end or start <= previous_end:
            raise ValueError('Periods must be ordered, disjoint dates after the statistics freeze')
        previous_end = end


def partitions(frame, periods=None):
    periods = DEFAULT_PERIODS if periods is None else periods
    dates = frame.date.astype(str)
    known = pd.to_datetime(frame.label_known, utc=True)
    result = {}
    for name, (start, end) in periods.items():
        cut = pd.Timestamp(end, tz='Asia/Seoul') + pd.Timedelta(days=1)
        result[name] = (dates.between(start, end) & (known < cut)).values
    return result


def design(source, lab, output, freeze=FREEZE):
    module_path = lab / 'scripts/stop_features.py'
    spec = importlib.util.spec_from_file_location('causal_stop_features', module_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    frames, seats, pools, receipts = [], [], [], []
    meta = json.loads((source / 'routes.json').read_text())
    for route, metadata in meta.items():
        samples = pd.read_pickle(source / f'samples_{route}_causal.pkl')
        samples['sample_row'] = np.arange(len(samples))
        labels = pd.read_pickle(source / f'alight_post_{route}.pkl')
        frozen = frozen_rows(labels, freeze)
        stats = frozen.groupby(['stop', 'band']).agg(t_alight_mean=('post_alight', 'mean'),
                    t_after_mean=('after', 'mean'), t_arrive_mean=('arrive', 'mean'))
        selected = labels[['trip', 'chain', 'stop', 'band', 'after', 'depart', 't_depart', 't_known']].rename(
            columns={'chain': 'origin_chain', 'stop': 'target', 'depart': 'true_depart',
                     't_known': 'label_known', 't_depart': 'departure_time'})
        if selected.duplicated(['trip', 'origin_chain', 'target']).any():
            raise ValueError('Duplicate identity label')
        frame = samples.merge(selected, on=['trip', 'origin_chain', 'target'], validate='many_to_one')
        origin = pd.to_datetime(frame.t_pred, utc=True)
        frame = frame[(origin < frame.departure_time) & (origin < frame.label_known)].reset_index(drop=True)
        frame['label'] = frame.true_depart
        local = pd.to_datetime(frame.t_pred, utc=True).dt.tz_convert('Asia/Seoul')
        frame['minute'] = local.dt.hour * 60 + local.dt.minute
        frame['weekday'] = local.dt.day_of_week
        frame = module.add_entry_columns(frame, metadata['stops']).join(stats, on=['target', 'band'])
        x50 = np.load(source / f'X50_{route}_causal.npy', mmap_mode='r')[frame.sample_row.values]
        x = np.column_stack([x50, frame[SEAT_COLUMNS + TARGET_STATS + ENTRY].values.astype('float32')])
        hour = frame.hour.values
        times = np.column_stack([np.stack([(hour >= a) & (hour < b) for a, b in TIME_BINS], axis=1),
                                 frame.weekend, frame.weekend * ((hour >= 7) & (hour < 9)),
                                 frame.weekend * ((hour >= 17) & (hour < 20))]).astype('float32')
        p = np.column_stack([x50, frame[OBS + EST + PATH + TODAY].values.astype('float32'), times,
                             frame.h.values.astype('int64')])
        if x.shape[1] != 88 or p.shape[1] != 80:
            raise ValueError('Feature dimension mismatch')
        # Store all target means as the artifact actually used, including support.
        stats.reset_index().to_json(output / f'target-means-{route}.json', orient='records', double_precision=15)
        receipts.append(dict(route=route, rows=len(frame), frozen_stats_rows=len(frozen),
                             missing_target_statistics_rows=int(frame[TARGET_STATS].isna().any(axis=1).sum()),
                             frozen_max_known=str(frozen.t_known.max())))
        frames.append(frame)
        seats.append(x)
        pools.append(p)
        print(json.dumps(receipts[-1]), flush=True)
    frame = pd.concat(frames, ignore_index=True)
    x, p = np.concatenate(seats), np.concatenate(pools)
    frame.to_pickle(output / 'frame.pkl')
    np.save(output / 'seat88.npy', x)
    np.save(output / 'probability80.npy', p)
    (output / 'feature-receipt.json').write_text(json.dumps(dict(freeze=str(freeze), routes=receipts), indent=2))
    return frame, x, p


def train(source, lab, output, old_models, freeze=FREEZE, periods=None, causal_control=None):
    periods = DEFAULT_PERIODS if periods is None else periods
    validate_periods(periods, freeze)
    calibration = json.loads((source / 'calibration-receipt.json').read_text())
    if pd.Timestamp(calibration['freeze_exclusive']) != freeze:
        raise ValueError('Training freeze differs from calibration freeze')
    sample_contract = source / 'sample-contract.json'
    if sample_contract.exists() and pd.Timestamp(json.loads(sample_contract.read_text())['freeze']) != freeze:
        raise ValueError('Training freeze differs from sample statistics freeze')
    if output.exists():
        raise ValueError('Output exists')
    output.mkdir(parents=True)
    (output / 'experiment.json').write_text(json.dumps(dict(freeze=str(freeze), periods=periods), indent=2))
    frame, x, p = design(source, lab, output, freeze)
    masks = partitions(frame, periods)
    if not all(m.any() for m in masks.values()):
        raise ValueError('Empty time partition')
    for a, b in [('train', 'dev'), ('train', 'final'), ('dev', 'final')]:
        left = set(zip(frame.route.values[masks[a]], frame.trip.values[masks[a]]))
        right = set(zip(frame.route.values[masks[b]], frame.trip.values[masks[b]]))
        if left & right:
            raise ValueError('A vehicle trip crosses time partitions')
    fit, dev, final = (masks[k] for k in ['train', 'dev', 'final'])
    y = frame.after.values
    report = dict(target='after-alighting-estimated', criterion='overall row-weighted MAE',
                  freeze=str(freeze), periods=periods,
                  availability_basis='declared input_confirmed_at; actual DB commit ordering unverified',
                  final_period_used_for_selection=False, counts={k:int(v.sum()) for k,v in masks.items()},
                  candidate_scores={}, current_model_control_is_not_causally_retrained=True)
    with threadpool_limits(limits=1):
        models = {}
        for loss in ['squared_error', 'absolute_error']:
            model = HistGradientBoostingRegressor(loss=loss, max_iter=400, learning_rate=0.05,
                       max_leaf_nodes=63, min_samples_leaf=200, l2_regularization=1.,
                       early_stopping=False, random_state=0).fit(x[fit], y[fit])
            models[loss] = model
            joblib.dump(model, output / f'{loss}.joblib')
            print('fit', loss, flush=True)
        classes = np.where(np.round(y[fit]) <= 0, 0, np.where(np.round(y[fit]) <= 2, 1, 2))
        gate = HistGradientBoostingClassifier(max_iter=160, learning_rate=.1, max_depth=4,
                  max_leaf_nodes=15, min_samples_leaf=40, l2_regularization=1.,
                  early_stopping=False, random_state=20261025).fit(x[fit], classes)
        if gate.classes_.tolist() != [0, 1, 2]:
            raise ValueError('Missing seat class in training')
        middle = float(np.round(y[fit])[classes == 1].mean())
        joblib.dump(gate, output / 'gate.joblib')
        candidates = {'squared': models['squared_error'], 'absolute': models['absolute_error']}
        for w in [.25, .5, .75]:
            candidates[f'blend_{w}'] = blend_experts(models['squared_error'], models['absolute_error'], w)
        dev_gate = gate.predict_proba(x[dev])
        for name, model in candidates.items():
            pred = gate_base(model.predict(x[dev]), dev_gate, middle)
            report['candidate_scores'][name] = metrics(pred, y[dev])
        chosen = min(report['candidate_scores'], key=lambda n: report['candidate_scores'][n]['mae'])
        expert = candidates[chosen]
        # Selection is fixed and persisted before final-period truth is scored.
        selection = dict(chosen=chosen, middle=middle, scores=report['candidate_scores'])
        (output / 'selection.json').write_text(json.dumps(selection, indent=2))
        joblib.dump(expert, output / 'expert.joblib')
        pred = gate_base(expert.predict(x[final]), gate.predict_proba(x[final]), middle)
        report['selected'] = chosen
        report['final'] = metrics(pred, y[final])
        final_dates = frame.date.astype(str).values[final]
        report['final_by_date'] = {day: metrics(pred[final_dates == day], y[final][final_dates == day])
                                    for day in sorted(set(final_dates))}
        report['final_by_route'] = {}
        for route in sorted(frame.route.unique()):
            m = frame.route.values[final] == route
            report['final_by_route'][route] = metrics(pred[m], y[final][m])
        report['current_seats_control'] = metrics(frame.current.values[final], y[final])
        if causal_control is not None:
            control_expert = joblib.load(causal_control / 'expert.joblib')
            control_gate = joblib.load(causal_control / 'gate.joblib')
            control_middle = json.loads((causal_control / 'selection.json').read_text())['middle']
            control_pred = gate_base(control_expert.predict(x[final]),
                                     control_gate.predict_proba(x[final]), control_middle)
            report['previous_causal_model_on_new_frozen_features'] = metrics(control_pred, y[final])
            report['previous_causal_model_by_route'] = {
                route: metrics(control_pred[frame.route.values[final] == route],
                               y[final][frame.route.values[final] == route])
                for route in sorted(frame.route.unique())}
            report['causal_control_hashes'] = {n: sha256(causal_control / n)
                                               for n in ['expert.joblib', 'gate.joblib', 'selection.json']}
        old_base = joblib.load(old_models / 'gb_after_entry.joblib')
        old_gate = joblib.load(old_models / 'gate_after.joblib')
        old_middle = json.loads((old_models / 'gate_constants.json').read_text())['middle']
        old_pred = gate_base(old_base.predict(x[final]), old_gate.predict_proba(x[final]), old_middle)
        report['old_research_control_on_same_causal_features'] = metrics(old_pred, y[final])
        previous_expert_path = old_models.parents[2] / 'combined-mae-blend-20261104/expert.joblib'
        if previous_expert_path.exists():
            previous = joblib.load(previous_expert_path)
            previous_pred = gate_base(previous.predict(x[final]), old_gate.predict_proba(x[final]), old_middle)
            report['previous_combined_control_on_same_causal_features'] = metrics(previous_pred, y[final])
        restored = joblib.load(output / 'expert.joblib')
        replay = gate_base(restored.predict(x[final]), gate.predict_proba(x[final]), middle)
        if not np.array_equal(pred, replay):
            raise ValueError('Saved-model predictions changed')
        report['saved_model_parity'] = True
        pool = HistGradientBoostingClassifier(max_iter=300, learning_rate=.05, max_leaf_nodes=31,
                   min_samples_leaf=1000, l2_regularization=1., early_stopping=False,
                   random_state=0).fit(p[fit], (frame.label.values[fit] == 0).astype(int))
        joblib.dump(pool, output / 'pool.joblib')
        full = pool.predict_proba(p[final])[:,1]
        report['departure_full_brier'] = float(np.square(full - (frame.label.values[final] == 0)).mean())
        np.save(output / 'final_predictions.npy', pred)
    report['model_hashes'] = {n:sha256(output/n) for n in ['expert.joblib','gate.joblib','pool.joblib']}
    report['feature_hashes'] = {n:sha256(output/n) for n in ['frame.pkl','seat88.npy','probability80.npy']}
    report['selection_sha256'] = sha256(output/'selection.json')
    report['source_receipt_sha256'] = sha256(source/'source-receipt.json')
    report['calibration_receipt_sha256'] = sha256(source/'calibration-receipt.json')
    (output / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ['source', 'lab', 'output', 'old-models']:
        parser.add_argument('--'+arg, type=Path, required=True)
    parser.add_argument('--freeze', default=str(FREEZE.date()))
    parser.add_argument('--periods', type=Path, help='JSON containing train, dev, final date pairs')
    parser.add_argument('--causal-control', type=Path)
    args = parser.parse_args()
    train(args.source, args.lab, args.output, args.old_models,
          pd.Timestamp(args.freeze, tz='Asia/Seoul'),
          json.loads(args.periods.read_text()) if args.periods else None, args.causal_control)
