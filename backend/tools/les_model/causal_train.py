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


def partitions(frame):
    dates = frame.date.astype(str)
    known = pd.to_datetime(frame.label_known, utc=True)
    train = dates.between('2026-10-12', '2026-10-16') & (known < pd.Timestamp('2026-10-17', tz='Asia/Seoul'))
    dev = dates.between('2026-10-19', '2026-10-21') & (known < pd.Timestamp('2026-10-22', tz='Asia/Seoul'))
    test = dates.between('2026-10-22', '2026-10-27')
    return dict(train=train.values, dev=dev.values, final=test.values)


def design(source, lab, output):
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
        frozen = frozen_rows(labels)
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
    (output / 'feature-receipt.json').write_text(json.dumps(dict(freeze=str(FREEZE), routes=receipts), indent=2))
    return frame, x, p


def train(source, lab, output, old_models):
    if output.exists():
        raise ValueError('Output exists')
    output.mkdir(parents=True)
    frame, x, p = design(source, lab, output)
    masks = partitions(frame)
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
        report['final_by_route'] = {}
        for route in sorted(frame.route.unique()):
            m = frame.route.values[final] == route
            report['final_by_route'][route] = metrics(pred[m], y[final][m])
        report['current_seats_control'] = metrics(frame.current.values[final], y[final])
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
    args = parser.parse_args()
    train(args.source, args.lab, args.output, args.old_models)
