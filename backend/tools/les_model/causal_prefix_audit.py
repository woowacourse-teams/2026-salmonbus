"""Rebuild a real route with future observations removed and compare past inputs."""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

from causal_events import build_events
from causal_prepare import samples, frozen_rows
from contracts import SEAT_COLUMNS, TARGET_STATS, ENTRY


def audit(source, lab, model, output, route, cut):
    if output.exists():
        raise ValueError('Audit output exists')
    output.mkdir(parents=True)
    cutoff = pd.Timestamp(cut, tz='Asia/Seoul')
    metadata = json.loads((source / 'routes.json').read_text())
    (output / 'routes.json').write_text(json.dumps({route: metadata[route]}))
    obs = pd.read_pickle(source / f'obs_{route}.pkl')
    obs = obs[obs.received < cutoff].copy()
    rebuilt, events = build_events(obs, metadata[route])
    rebuilt.to_pickle(output / f'obs_{route}_trips.pkl')
    events.to_pickle(output / f'events_{route}.pkl')
    batch = pd.read_pickle(source / f'batches_{route}.pkl')
    batch[batch.received < cutoff].to_pickle(output / f'batches_{route}.pkl')
    samples(output, lab, output / 'generated')
    prefix = pd.read_pickle(output / f'samples_{route}_causal.pkl')
    prefix['sample_row'] = np.arange(len(prefix))
    full = pd.read_pickle(model / 'frame.pkl')
    full['vector_row'] = np.arange(len(full))
    full = full[(full.route == route) & (pd.to_datetime(full.label_known, utc=True) < cutoff)]
    keys = ['origin_observation_id', 'target']
    matched = prefix.merge(full[keys + ['vector_row', 'band']], on=keys, validate='one_to_one')
    if len(matched) != len(full) or len(full) == 0:
        raise ValueError('Past eligible cases changed after deleting future observations')
    local = pd.to_datetime(matched.t_pred, utc=True).dt.tz_convert('Asia/Seoul')
    matched['minute'] = local.dt.hour * 60 + local.dt.minute
    matched['weekday'] = local.dt.day_of_week
    spec = importlib.util.spec_from_file_location('audit_stop_features', lab / 'scripts/stop_features.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    matched = module.add_entry_columns(matched, metadata[route]['stops'])
    # The calibration data and model are already frozen before both cutoffs.
    post = frozen_rows(pd.read_pickle(source / f'alight_post_{route}.pkl'))
    stats = post.groupby(['stop', 'band']).agg(t_alight_mean=('post_alight', 'mean'),
                       t_after_mean=('after', 'mean'), t_arrive_mean=('arrive', 'mean'))
    matched = matched.join(stats, on=['target', 'band'])
    x50 = np.load(output / f'X50_{route}_causal.npy', mmap_mode='r')[matched.sample_row.values]
    actual = np.column_stack([x50, matched[SEAT_COLUMNS + TARGET_STATS + ENTRY].values.astype('float32')])
    expected = np.load(model / 'seat88.npy', mmap_mode='r')[matched.vector_row.values]
    same = (actual == expected) | (np.isnan(actual) & np.isnan(expected))
    report = dict(route=route, removed_at_or_after=str(cutoff), cases=len(matched),
                  feature_cells=int(same.size), differing_cells=int((~same).sum()),
                  passed=bool(same.all()), scope='real route prefix input invariance; not actual DB commit-time proof')
    (output / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)
    if not report['passed']:
        raise ValueError('Future removal changed past model inputs')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ['source', 'lab', 'model', 'output']:
        parser.add_argument('--' + arg, type=Path, required=True)
    parser.add_argument('--route', default='3330')
    parser.add_argument('--cut', default='2026-10-17')
    a = parser.parse_args()
    audit(a.source, a.lab, a.model, a.output, a.route, a.cut)
