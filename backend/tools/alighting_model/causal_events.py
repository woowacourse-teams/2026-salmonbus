"""Identity-safe AlightingAware pass proxies, available only after a closure witness."""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


def build_events(observations, metadata):
    o = observations.sort_values(['vid', 'received', 'batch', 'observation_id']).reset_index(drop=True)
    same = o.vid.eq(o.vid.shift()) & o.rv.eq(o.rv.shift())
    elapsed = o.received.diff().dt.total_seconds()
    physical_gap = o.source_received.diff().dt.total_seconds()
    back = o.passed.lt(o.passed.shift())
    direction = ((o.stop_order > metadata['turn']) |
                 ((o.stop_order == metadata['turn']) & (o.state == 2)) |
                 ((o.stop_order == 1) & (o.state != 2)))
    changed_direction = direction.ne(direction.shift())
    new_trip = ~same | (elapsed > 1800) | back | changed_direction
    new_chain = new_trip | (elapsed > 90) | (physical_gap > 90) | (physical_gap < 0)
    o['trip'] = new_trip.cumsum().astype('int32')
    o['chain'] = new_chain.cumsum().astype('int32')
    o['capacity'] = o.seats.fillna(0).groupby(o.vid, observed=True).cummax()
    # Only actual boarding neighbors may close a target visit.
    real = sorted(s['order'] for s in metadata['stops'] if s['boardingAllowed'])
    rows = []
    for _, chain in o.groupby('chain', sort=False, observed=True):
        if len(chain) < 3:
            continue
        passed = chain.passed.values
        for prev, target, following in zip(real, real[1:], real[2:]):
            if passed[0] >= target or passed[-1] < following:
                continue
            pre = np.flatnonzero((passed >= prev) & (passed < target))
            post = np.flatnonzero((passed >= target) & (passed < following))
            witness = np.flatnonzero(passed >= following)
            if not len(pre) or not len(post) or not len(witness):
                continue
            a, d, w = chain.iloc[pre[-1]], chain.iloc[post[-1]], chain.iloc[witness[0]]
            if not pre[-1] < post[0] <= post[-1] < witness[0]:
                continue
            if not np.isfinite([a.seats, d.seats, w.capacity]).all():
                continue
            if not 0 <= a.seats <= w.capacity <= 70 or not 0 <= d.seats <= w.capacity:
                continue
            rows.append(dict(trip=int(a.trip), chain=int(a.chain), stop=target,
                             t_arrive=a.received, t_depart=d.received, t_known=w.received,
                             arrive=float(a.seats), depart=float(d.seats), capacity=float(w.capacity),
                             source_arrival_id=int(a.observation_id), source_departure_id=int(d.observation_id),
                             closure_id=int(w.observation_id), rv=int(a.rv)))
    ev = pd.DataFrame(rows)
    if ev.empty:
        raise ValueError('No closed, identity-safe events')
    if ev.duplicated(['trip', 'stop']).any():
        raise ValueError('Ambiguous repeated trip/stop; do not choose a favorable visit')
    if not ((ev.t_arrive < ev.t_depart) & (ev.t_depart < ev.t_known)).all():
        raise ValueError('Event ordering is invalid')
    ev['clear'] = (ev.depart >= 1) & (ev.arrive >= 1)
    ev['boarded'] = (ev.arrive - ev.depart).clip(lower=0)
    ev['onboard'] = ev.capacity - ev.arrive
    ev = ev.sort_values(['stop', 't_known']).reset_index(drop=True)
    local = ev.t_depart.dt.tz_convert('Asia/Seoul')
    ev['date'] = local.dt.date
    ev['minute'] = local.dt.hour * 60 + local.dt.minute
    ev['weekday'] = local.dt.day_of_week
    same_day_stop = ev.stop.eq(ev.stop.shift()) & ev.date.eq(ev.date.shift())
    ev['gap'] = np.where(same_day_stop, ev.t_depart.diff().dt.total_seconds() / 60, np.nan)
    ev['prev_full'] = np.where(same_day_stop, ev.depart.shift().eq(0).astype(float), np.nan)
    ev['dwell_n'] = 0
    ev['dwell_s'] = np.nan
    return o, ev


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((args.source / 'routes.json').read_text())
    for route, meta in metadata.items():
        obs, events = build_events(pd.read_pickle(args.source / f'obs_{route}.pkl'), meta)
        obs.to_pickle(args.source / f'obs_{route}_trips.pkl')
        events.to_pickle(args.source / f'events_{route}.pkl')
        events.to_pickle(args.source / f'apass_{route}.pkl')
        print(json.dumps(dict(route=route, observations=len(obs), closed_events=len(events))), flush=True)
