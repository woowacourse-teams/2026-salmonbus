"""Refit Les's expert for MAE while preserving Donkey's three-class gate.

Consumes only trusted, locally reconstructed pandas/joblib artifacts. The final
period is a development check, not an unseen or causal online evaluation.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil
import time

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from threadpoolctl import threadpool_limits

from contracts import gate_base, seat_design, sha256, verify_models
from export_bundle import boundary_cases, export_tree_model
from refit_pool import validate_bench, verify_environment


def metrics(prediction, target):
    error = prediction - target
    low = target <= 2
    return dict(rows=len(target), mae=float(np.abs(error).mean()),
                bias=float(error.mean()), under_by3=float((error <= -3).mean()),
                over_by3_low=float((error[low] >= 3).mean()) if low.any() else None)


def select_candidate(scores):
    """Only the selection period decides the candidate; prefer baseline on ties."""
    return min(scores, key=lambda name: scores[name]['selection']['mae'])


def blend_experts(original, absolute, weight):
    """Encode the weighted regressors as one additive numeric tree ensemble."""
    result = copy.deepcopy(original)
    result._baseline_prediction = ((1 - weight) * original._baseline_prediction
                                   + weight * absolute._baseline_prediction)
    result._predictors = []
    for model, coefficient in [(original, 1 - weight), (absolute, weight)]:
        steps = copy.deepcopy(model._predictors)
        for step in steps:
            for tree in step:
                tree.nodes['value'][tree.nodes['is_leaf'].astype(bool)] *= coefficient
        result._predictors.extend(steps)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ['run', 'output']:
        parser.add_argument('--' + arg, type=Path, required=True)
    parser.add_argument('--expert-reference', type=Path,
                        help='Reuse a trusted local first-round artifact, after input-hash validation')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve earlier runs: output already exists')
    verify_environment(args.run)
    models = args.run / 'lab/models/reproduced'
    verify_models(models)
    derived = args.run / 'lab/data/derived'
    frame = pd.read_pickle(derived / 'bench_frame.pkl')
    xtime = np.load(derived / 'bench_X_time.npy', mmap_mode='r')
    validate_bench(frame, xtime)
    keep = np.flatnonzero(frame.is_keep.values)
    test = np.flatnonzero(frame.is_test.values)
    dates = frame.iloc[test].date.astype(str).values
    selection = dates <= '2026-10-16'
    check = dates >= '2026-10-19'
    if not selection.any() or not check.any() or (selection & check).any() or not (selection | check).all():
        raise ValueError('Unexpected date partition')
    target = frame.after.values[test]
    if not np.isfinite(target).all():
        raise ValueError('Nonfinite targets')
    args.output.mkdir(parents=True)
    start = time.monotonic()
    report = dict(criterion='overall row-weighted MAE', target='after-alighting-estimated',
                  selection_period=['2026-10-12', '2026-10-16'],
                  check_period=['2026-10-19', '2026-10-21'],
                  unseen_period=False, online_causal_validation=False,
                  training_rows=len(keep), scores={}, threads=1,
                  input_hashes={n: sha256(derived / n) for n in ['bench_frame.pkl', 'bench_X_time.npy']})
    with threadpool_limits(limits=1):
        xtest = seat_design(frame, xtime, test)
        gate = joblib.load(models / 'gate_after.joblib')
        middle = json.loads((models / 'gate_constants.json').read_text())['middle']
        probabilities = gate.predict_proba(xtest)
        predictions = {'baseline': gate_base(frame.gb_after_entry.values[test], probabilities, middle)}
        # Absolute loss fits the conditional median using unrounded target seats.
        # Training dates and features are unchanged; no evaluation targets are fit.
        expert = HistGradientBoostingRegressor(loss='absolute_error', max_iter=400,
                    learning_rate=0.05, max_leaf_nodes=63, min_samples_leaf=200,
                    l2_regularization=1.0, early_stopping=False, random_state=0)
        if args.expert_reference is None:
            print('Training one absolute-error expert, 400 iterations', flush=True)
            xtrain = seat_design(frame, xtime, keep)
            expert.fit(xtrain, frame.after.values[keep])
            del xtrain
        else:
            prior = json.loads((args.expert_reference / 'comparison.json').read_text())
            if prior['input_hashes'] != report['input_hashes']:
                raise ValueError('Prior expert used different data')
            path = args.expert_reference / 'expert-400.joblib'
            expert = joblib.load(path)
            if expert.loss != 'absolute_error' or expert.n_iter_ != 400 or expert.n_features_in_ != 88:
                raise ValueError('Unexpected prior expert contract')
            report['reused_expert_sha256'] = sha256(path)
        joblib.dump(expert, args.output / 'expert-400.joblib')
        for iteration, raw in enumerate(expert.staged_predict(xtest), start=1):
            if iteration in [200, 400]:
                predictions[f'absolute_{iteration}'] = gate_base(raw, probabilities, middle)
        blends = {}
        if args.expert_reference is not None:
            original = joblib.load(models / 'gb_after_entry.joblib')
            original_raw = original.predict(xtest)
            absolute_raw = expert.predict(xtest)
            for weight in [0.25, 0.5, 0.75]:
                name = f'blend_{weight}'
                blended = blend_experts(original, expert, weight)
                raw = blended.predict(xtest)
                expected = (1 - weight) * original_raw + weight * absolute_raw
                if not np.allclose(raw, expected, atol=1e-9, rtol=0):
                    raise ValueError('Tree composition differs from weighted predictions')
                predictions[name] = gate_base(raw, probabilities, middle)
                blends[name] = blended
        for name, pred in predictions.items():
            report['scores'][name] = {part: metrics(pred[mask], target[mask]) for part, mask in
                                     [('selection', selection), ('check', check), ('all', np.ones(len(test), dtype=bool))]}
            report['scores'][name]['by_route'] = {
                str(route): metrics(pred[mask], target[mask])
                for route in sorted(frame.route.unique())
                if (mask := frame.route.values[test] == route).any()}
        chosen = select_candidate(report['scores'])
        # A held-back-period regression is a reason to retain the baseline,
        # never an opportunity to retune against that period.
        accepted = (chosen != 'baseline' and report['scores'][chosen]['check']['mae'] <
                    report['scores']['baseline']['check']['mae'])
        report.update(selected_on_development=chosen, accepted=accepted,
                      retained='baseline' if not accepted else chosen)
        if accepted:
            if chosen in blends:
                expert = blends[chosen]
            else:
                count = int(chosen.split('_')[1])
                expert._predictors = expert._predictors[:count]
            count = len(expert._predictors)
            joblib.dump(expert, args.output / 'expert.joblib')
            restored = joblib.load(args.output / 'expert.joblib')
            replay = gate_base(restored.predict(xtest), probabilities, middle)
            if not np.array_equal(replay, predictions[chosen]):
                raise ValueError('Saved model differs from selected candidate')
            bundle = args.output / 'bundle'
            shutil.copytree(args.run / 'java-inference-bundle-v2', bundle)
            pool = joblib.load(args.run / 'pool-serving-reference/pool_gb.joblib')
            golden = json.loads((bundle / 'golden.json').read_text())
            sx = np.array(golden['seat_inputs'], dtype='float32')
            b = np.maximum(restored.predict(sx), 0)
            p = gate.predict_proba(sx)
            golden.update(expected_base=b.tolist(), expected_gate_probabilities=p.tolist(),
                          expected_seats=gate_base(b, p, middle).tolist())
            (bundle / 'base.json').write_text(json.dumps(export_tree_model(restored, 'regression'), allow_nan=False))
            (bundle / 'golden.json').write_text(json.dumps(golden, allow_nan=False))
            (bundle / 'boundaries.json').write_text(json.dumps(boundary_cases(restored, gate, pool, golden), allow_nan=False))
            manifest = json.loads((bundle / 'manifest.json').read_text())
            manifest['parent_reference_manifest_sha256'] = manifest.pop('reference_manifest_sha256')
            manifest.update(model='les-gate-base-and-pool', serving_ready=False,
                            parent_manifest_sha256=sha256(args.run / 'java-inference-bundle-v2/manifest.json'),
                            optimization=dict(expert_recipe=chosen, iterations=count,
                                              target_rounding=False, criterion='MAE'),
                            pending=['causal online replay', 'new-period evaluation', 'activation verification receipts'])
            del manifest['trained_model_hashes']['gb_after_entry.joblib']
            manifest['trained_model_hashes']['expert.joblib'] = sha256(args.output / 'expert.joblib')
            (bundle / 'optimization-provenance.json').write_text(json.dumps(report, indent=2))
            manifest['files'] = {p.name: sha256(p) for p in sorted(bundle.iterdir()) if p.name != 'manifest.json'}
            (bundle / 'manifest.json').write_text(json.dumps(manifest, indent=2))
            report.update(model_sha256=sha256(args.output / 'expert.joblib'),
                          bundle_manifest_sha256=sha256(bundle / 'manifest.json'), saved_model_parity=True)
        else:
            # A failed optimization still yields the best combined baseline;
            # never rename a worse experiment as the chosen model.
            shutil.copytree(args.run / 'java-inference-bundle-v2', args.output / 'bundle')
            report['bundle_manifest_sha256'] = sha256(args.output / 'bundle/manifest.json')
    report['elapsed_seconds'] = time.monotonic() - start
    (args.output / 'comparison.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
