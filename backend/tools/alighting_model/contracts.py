"""Frozen contracts for the 2026-11-04 AlightingAware handoff, not a new model recipe."""
import ast
import hashlib
from pathlib import Path

import numpy as np

ROUTES = ['3330', '1650', '9007', '9300', '6011', '3000', '5600', '3500']
POOL_PARAMETERS = dict(max_iter=300, learning_rate=0.05, max_leaf_nodes=31,
                       min_samples_leaf=1000, l2_regularization=1.0,
                       early_stopping=False, random_state=0)
SEAT_COLUMNS = ['hour', 'h', 'current', 'capacity', 'pos', 'q_full_since',
                'q_since_clear', 'q_unknown', 'q_left', 'q_ahead_n', 'q_ahead_seats',
                'q_left_mine', 'q_last_full', 'p_path_net', 'p_path_left', 'p_path_full_n',
                'p_target_full_rate', 'n_near_gap', 'n_near_seats', 'n_behind_gap',
                'n_trend5', 'p_target_mean_dep', 'd_path_ratio', 'd_path_surplus',
                'd_path_adj', 'd_dir_ratio', 'd_vehicle_ratio', 'd_vehicle_n',
                'weekend', 'minute', 'weekday']
TARGET_STATS = ['t_alight_mean', 't_after_mean', 't_arrive_mean']
ENTRY = ['entry_rank', 'entry_via_run', 'island', 'via_between']
OBS = ['q_last_full', 'q_full_since', 'q_since_clear', 'q_unknown', 'q_ahead_n', 'q_ahead_seats']
EST = ['q_left', 'q_left_mine']
PATH = ['p_path_net', 'p_path_left', 'p_path_full_n']
TODAY = ['d_path_ratio', 'd_path_surplus', 'd_path_adj', 'd_dir_ratio', 'd_vehicle_ratio']
TIME_BINS = [(5, 7), (7, 8), (8, 9), (9, 11), (11, 14), (14, 17),
             (17, 18), (18, 19), (19, 20), (20, 22)]
# Names describe original array positions; zero columns remain present.
X50 = ['intercept', 'morning', 'evening', 'reserved_zero_3', 'seat_capacity_ratio',
       'current_full', 'seats_capped20_ratio', 'crowd_1', 'crowd_2', 'crowd_3',
       'crowd_4', 'capacity_over68', 'slope', 'slope_missing', 'full_streak',
       'preceding_full', 'preceding_seat_ratio', 'preceding_missing', 'reserved_zero_18',
       'target_position'] + [f'position_basis_{i}' for i in range(8)] + [
       f'reserved_zero_{i}' for i in range(28, 31)] + ['target_down'] + [
       f'morning_basis_{i}' for i in range(8)] + ['morning_down'] + [
       f'evening_basis_{i}' for i in range(8)] + ['evening_down']
TIME = [f'hour_{a}_{b}' for a, b in TIME_BINS] + ['weekend', 'weekend_morning', 'weekend_evening']
SEAT88 = X50 + SEAT_COLUMNS + TARGET_STATS + ENTRY
FULL80 = X50 + OBS + EST + PATH + TODAY + TIME + ['h']
SOURCE_HASHES = {
    'scripts/scoreboard.py': '7282e4e4d823a7505e81aef599ab7a3485a7a3ca213f57630d3fa652c26dfbe3',
    'scripts/gate_q35.py': 'd93297d4e026d2a46b0580f88877e2f55bfa2534e4e40a5070ced40eecc3f3b1',
    'recover/analysis/samples3.py': 'eae3052b7b865ada2fcb2c2679c52f3a13aa733bb047185d2282a2948dee91c9',
    'recover/analysis/exp2.py': 'af88678d0e17c23bbc795705de59135c7e15b5aede596e5ded2c4ccb3e2f5c1e',
}
MODEL_HASHES = {
    'gb_after_entry.joblib': 'd0f5d406b4db2ce2cd68ef7ac6d205c663cb08c05c78239045d747ca99f4f7a6',
    'gate_after.joblib': '72f8d7fb173887bbaa36500aa299b69d9232002f70ecf67ece1fe8ad337ac400',
    'gate_constants.json': 'bfcaea38cc804395b2866d36e9807a4157b2ede5c78ab106169e15cbd9016dfa',
}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def literals(path):
    values = {}
    for node in ast.parse(Path(path).read_text()).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            try:
                values[node.targets[0].id] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                pass
    return values


def verify_sources(lab):
    """Read source, never execute a handoff script during contract inspection."""
    hashes = {}
    for relative, expected in SOURCE_HASHES.items():
        actual = sha256(Path(lab) / relative)
        if expected is not None and actual != expected:
            raise ValueError(f'Handoff source changed: {relative}')
        hashes[relative] = actual
    for script in ['scoreboard.py', 'gate_q35.py']:
        constants = literals(Path(lab) / 'scripts' / script)
        for name, expected in [('SEAT_COLUMNS', SEAT_COLUMNS), ('TARGET_STATS', TARGET_STATS), ('ENTRY', ENTRY)]:
            if constants.get(name) != expected:
                raise ValueError(f'Feature order changed: {script}:{name}')
    exp = literals(Path(lab) / 'recover/analysis/exp2.py')
    for name, expected in [('OBS', OBS), ('EST', EST), ('PATH', PATH), ('TODAY', TODAY)]:
        if exp.get(name) != expected:
            raise ValueError(f'Probability feature order changed: {name}')
    tree = ast.parse((Path(lab) / 'scripts/scoreboard.py').read_text())
    node = next(n.value for n in tree.body if isinstance(n, ast.Assign)
                and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'GB_CLASSIFIER')
    parameters = {k.arg: ast.literal_eval(k.value) for k in node.keywords}
    if parameters != POOL_PARAMETERS:
        raise ValueError('Probability training parameters changed')
    return hashes


def verify_models(directory):
    for name, expected in MODEL_HASHES.items():
        if sha256(Path(directory) / name) != expected:
            raise ValueError(f'Frozen seat model changed: {name}')


def seat_design(frame, x_time, rows):
    return np.column_stack([x_time[rows, :50],
                            frame.iloc[rows][SEAT_COLUMNS + TARGET_STATS + ENTRY].values.astype('float32')])


def pool_design(frame, x_time, rows):
    # Original np.column_stack of float32 X_time and int64 horizon promotes to float64.
    return np.column_stack([x_time[rows], frame.h.values[rows]])


def gate_base(base, probabilities, middle):
    probabilities = np.asarray(probabilities)
    if probabilities.ndim != 2 or probabilities.shape[1] != 3:
        raise ValueError('Gate requires class order [0, 1, 2]')
    return np.clip(probabilities[:, 1] * middle + probabilities[:, 2] * np.clip(base, 0, None), 0, 70)


def feature_contract():
    return dict(schema='alighting-feature-contract-v1', seat_features=SEAT88,
                probability_features=FULL80, seat_dtype='float32',
                probability_dtype='float64 from float32 X_time + int64 h',
                seat_target='after-alighting-estimated', probability_target='departure-zero',
                gate_classes=[0, 1, 2], gate_label_rounding='numpy.round before classification',
                prior_shift=False, x50_zero_indices=[3, 18, 28, 29, 30],
                source_reference='2026-11-04 AlightingAware handoff; samples3.py and exp2.py',
                serving_ready=False)
