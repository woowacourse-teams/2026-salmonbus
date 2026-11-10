"""Create local row-level reference cases for a later Java port.

Only consume trusted locally generated pickle files. Output is NOT an activatable
serving bundle: online state, stop-time statistics and Java parity remain pending.
"""
import argparse
import json
import os
from pathlib import Path
import tempfile

import joblib
import numpy as np
import pandas as pd
from threadpoolctl import threadpool_limits

from contracts import (MODEL_HASHES, ROUTES, feature_contract, gate_base, pool_design,
                       seat_design, sha256, verify_models, verify_sources)
from refit_pool import validate_bench, verify_environment


def select_rows(frame, per_route=64):
    """Stable route coverage; also include low-label cases when a route has any."""
    selected = set()
    for route in ROUTES:
        route_rows = np.flatnonzero(frame.is_test.values & (frame.route.values == route))
        if len(route_rows) == 0:
            raise ValueError(f'Missing evaluation route: {route}')
        for subset in [route_rows, route_rows[frame.after.values[route_rows] <= 2]]:
            if len(subset):
                selected.update(subset[np.unique(np.linspace(0, len(subset) - 1,
                                                            min(per_route, len(subset)), dtype=int))].tolist())
    return np.asarray(sorted(selected), dtype=int)


def json_values(values):
    """JSON null is exclusively a missing feature, not zero; reject infinity."""
    values = np.asarray(values)
    if np.isinf(values).any():
        raise ValueError('Infinite feature or prediction cannot be exported')
    return np.where(np.isnan(values), None, values).tolist()


def expected_gate(frame, candidate, rows):
    # Identity join must be one-to-one; never use candidate row position as identity.
    if candidate[['route', 'sample_row']].duplicated().any():
        raise ValueError('Duplicate candidate identity')
    keys = frame.iloc[rows][['route', 'sample_row']]
    joined = keys.merge(candidate[['route', 'sample_row', 'seats']], how='left',
                        on=['route', 'sample_row'], validate='one_to_one', sort=False)
    if joined.seats.isna().any():
        raise ValueError('Missing saved gate prediction')
    return joined.seats.to_numpy()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--handoff-lab', type=Path, required=True)
    parser.add_argument('--pool-reference', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Output exists; select a new directory')
    environment = verify_environment(args.run)
    sources = verify_sources(args.handoff_lab)
    models = args.run / 'lab/models/reproduced'
    verify_models(models)
    receipt = json.loads((args.pool_reference / 'receipt.json').read_text())
    if sha256(args.pool_reference / 'pool_gb.joblib') != receipt['model_sha256']:
        raise ValueError('Pool model digest mismatch')
    derived = args.run / 'lab/data/derived'
    for name, digest in receipt['input_hashes'].items():
        if sha256(derived / name) != digest:
            raise ValueError(f'Bench changed since probability verification: {name}')
    frame = pd.read_pickle(derived / 'bench_frame.pkl')
    x_time = np.load(derived / 'bench_X_time.npy', mmap_mode='r')
    validate_bench(frame, x_time)
    rows = select_rows(frame)
    x_seat, x_pool = seat_design(frame, x_time, rows), pool_design(frame, x_time, rows)
    candidate = pd.read_pickle(derived / 'candidate_gate_base.pkl')
    expected = expected_gate(frame, candidate, rows)
    constants = json.loads((models / 'gate_constants.json').read_text())
    base_model = joblib.load(models / 'gb_after_entry.joblib')
    gate_model = joblib.load(models / 'gate_after.joblib')
    pool_model = joblib.load(args.pool_reference / 'pool_gb.joblib')
    if gate_model.classes_.tolist() != constants['classes'] or constants['classes'] != [0, 1, 2]:
        raise ValueError('Gate class order differs from the reference formula')
    if pool_model.classes_.tolist() != [0, 1]:
        raise ValueError('Pool class order differs from the reference formula')
    with threadpool_limits(limits=1):
        base = np.maximum(base_model.predict(x_seat), 0)
        probabilities = gate_model.predict_proba(x_seat)
        seats = gate_base(base, probabilities, constants['middle'])
        full = pool_model.predict_proba(x_pool)[:, 1]
    differences = dict(base=float(np.max(np.abs(base - frame.gb_after_entry.values[rows]))),
                       gate=float(np.max(np.abs(seats - expected))),
                       full=float(np.max(np.abs(full - frame.pool_gb.values[rows]))))
    if not all(np.isfinite(v) and v <= 1e-9 for v in differences.values()):
        raise ValueError(f'Reference predictions differ: {differences}')
    golden = dict(schema='alighting-reference-cases-v1', missing_feature_encoding='null means NaN',
                  seat_inputs=json_values(x_seat), probability_inputs=json_values(x_pool),
                  expected_base=json_values(base), expected_gate_probabilities=json_values(probabilities),
                  expected_seats=json_values(seats), expected_departure_full=json_values(full),
                  middle=constants['middle'],
                  keys=[dict(route=str(frame.route.iloc[i]), sample_row=int(frame.sample_row.iloc[i])) for i in rows])
    manifest = dict(schema='alighting-reference-manifest-v1', ticket='SAL-359', serving_ready=False,
                    exact_original_cohort=False, sources=sources, environment=environment,
                    model_hashes={**MODEL_HASHES, 'pool_gb.joblib': receipt['model_sha256']},
                    input_hashes={**receipt['input_hashes'], 'candidate_gate_base.pkl': sha256(derived / 'candidate_gate_base.pkl')},
                    training_rows=int(frame.is_keep.sum()), evaluation_rows=int(frame.is_test.sum()),
                    golden_rows=len(rows), max_absolute_difference=differences, tolerance=1e-9,
                    pending=['static feature statistics export', 'Java inference parity',
                             'causal online features', 'versioned settlement and API integration'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.alighting-reference-', dir=args.output.parent) as temp:
        stage = Path(temp)
        for name, payload in [('golden.json', golden), ('feature-contract.json', feature_contract())]:
            (stage / name).write_text(json.dumps(payload, allow_nan=False, indent=2) + '\n')
        manifest['files'] = {name: sha256(stage / name) for name in ['golden.json', 'feature-contract.json']}
        (stage / 'manifest.json').write_text(json.dumps(manifest, allow_nan=False, indent=2) + '\n')
        os.rename(stage, args.output)
    print(json.dumps(dict(golden_rows=len(rows), differences=differences, output=str(args.output), serving_ready=False)))


if __name__ == '__main__':
    main()
