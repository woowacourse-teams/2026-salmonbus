"""Save AlightingAware's previously evaluated pool_gb, preserving the original cohort and recipe.

Inputs must be trusted local reproduction outputs: pandas/joblib pickle loading can
execute code. This command does not accept network URLs or overwrite existing output.
"""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import resource
import tempfile
import time

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from threadpoolctl import threadpool_limits

from contracts import (POOL_PARAMETERS, ROUTES, feature_contract, pool_design,
                       sha256, verify_models, verify_sources)


def validate_bench(frame, matrix):
    if matrix.shape != (len(frame), 79) or matrix.dtype != np.float32:
        raise ValueError('Expected aligned float32 bench_X_time with 79 columns')
    if frame.h.dtype != np.int64:
        raise ValueError('Original horizon dtype must be int64')
    if not frame.index.equals(pd.RangeIndex(len(frame))):
        raise ValueError('Bench positional index changed')
    if frame[['route', 'sample_row']].duplicated().any():
        raise ValueError('Duplicate bench row key')
    for key in ['is_keep', 'is_test']:
        if frame[key].dtype != bool:
            raise ValueError('Cohort masks must be boolean')
    keep, test = frame.is_keep.values, frame.is_test.values
    if (keep & test).any() or not (keep | test).all() or not keep.any() or not test.any():
        raise ValueError('Training/evaluation cohort masks overlap or omit rows')
    if frame.route.drop_duplicates().tolist() != ROUTES:
        raise ValueError('Original route concatenation order changed')
    if not frame.label.between(0, 70).all() or not frame.h.between(1, 12).all():
        raise ValueError('Invalid labels or forecast horizon')
    if not np.isfinite(frame.pool_gb).all() or not frame.pool_gb.between(0, 1).all():
        raise ValueError('Reference probabilities are missing or invalid')
    if not (frame.date[keep].astype(str) < '2026-10-12').all():
        raise ValueError('Unexpected training date')
    if not frame.date[test].astype(str).between('2026-10-12', '2026-10-21').all():
        raise ValueError('Unexpected evaluation date')


def verify_environment(run):
    reference = json.loads((run / 'environment.json').read_text())
    actual = {name: importlib.metadata.version(name) for name in reference['packages']}
    if actual != reference['packages'] or platform.python_version() != reference['python']:
        raise ValueError('Use the pinned reproduction Python environment')
    return dict(python=platform.python_version(), packages=actual)


def compare_predictions(model, frame, matrix, batch_size=8192):
    maxima = dict(all=0.0, training=0.0, evaluation=0.0)
    brier_sum = 0.0
    for start in range(0, len(frame), batch_size):
        rows = np.arange(start, min(start + batch_size, len(frame)))
        predicted = model.predict_proba(pool_design(frame, matrix, rows))[:, 1]
        error = np.abs(predicted - frame.pool_gb.values[rows])
        if not np.isfinite(error).all():
            raise ValueError('Non-finite probability comparison')
        maxima['all'] = max(maxima['all'], float(error.max()))
        for name, mask in [('training', frame.is_keep.values[rows]), ('evaluation', frame.is_test.values[rows])]:
            if mask.any():
                maxima[name] = max(maxima[name], float(error[mask].max()))
        test = frame.is_test.values[rows]
        truth = (frame.label.values[rows][test] == 0).astype(float)
        # AlightingAware exp2.metrics clips probabilities only for the Brier calculation.
        brier_sum += float(np.square(np.clip(predicted[test], 1e-6, 1 - 1e-6) - truth).sum())
    if maxima['all'] > 1e-9:
        raise ValueError(f'Refit disagrees with saved predictions: {maxima}')
    return dict(max_absolute_difference=maxima, tolerance=1e-9,
                checked_rows=len(frame), evaluation_brier=brier_sum / int(frame.is_test.sum()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--handoff-lab', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run, output = args.run.resolve(), args.output.resolve()
    if output.exists():
        raise ValueError('Output exists; preserve prior artifacts and choose a new directory')
    started = time.monotonic()
    env = verify_environment(run)
    source = verify_sources(args.handoff_lab)
    models = run / 'lab/models/reproduced'
    verify_models(models)
    derived = run / 'lab/data/derived'
    inputs = {name: sha256(derived / name) for name in ['bench_frame.pkl', 'bench_X_time.npy']}
    frame = pd.read_pickle(derived / 'bench_frame.pkl')
    matrix = np.load(derived / 'bench_X_time.npy', mmap_mode='r')
    validate_bench(frame, matrix)
    keep = np.flatnonzero(frame.is_keep.values)
    print(json.dumps(dict(stage='train', rows=len(keep), evaluation_rows=int(frame.is_test.sum()), threads=1)), flush=True)
    with threadpool_limits(limits=1):
        model = HistGradientBoostingClassifier(**POOL_PARAMETERS).fit(
            pool_design(frame, matrix, keep), (frame.label.values[keep] == 0).astype(float))
        if model.classes_.tolist() != [0, 1]:
            raise ValueError('Unexpected probability class order')
        print('Verifying every training and evaluation prediction', flush=True)
        parity = compare_predictions(model, frame, matrix)
        verify_models(models)
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.alighting-pool-', dir=output.parent) as temp:
            stage = Path(temp)
            joblib.dump(model, stage / 'pool_gb.joblib')
            restored = joblib.load(stage / 'pool_gb.joblib')
            roundtrip = compare_predictions(restored, frame, matrix)
            receipt = dict(schema='alighting-pool-refit-v1', model='pool_gb', parameters=POOL_PARAMETERS,
                           source_hashes=source, input_hashes=inputs, environment=env,
                           training_rows=len(keep), evaluation_rows=int(frame.is_test.sum()),
                           parity=parity, saved_model_parity=roundtrip, threads=1,
                           elapsed_seconds=time.monotonic() - started,
                           max_rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                           max_rss_unit='bytes' if platform.system() == 'Darwin' else 'KiB',
                           model_sha256=sha256(stage / 'pool_gb.joblib'),
                           exact_original_cohort=False, serving_ready=False)
            (stage / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            (stage / 'feature-contract.json').write_text(json.dumps(feature_contract(), indent=2) + '\n')
            os.rename(stage, output)
    print(json.dumps(receipt, indent=2), flush=True)


if __name__ == '__main__':
    main()
