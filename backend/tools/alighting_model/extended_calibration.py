"""Inventory closure-confirmed label inputs for extended-hours model experiments.

Counts are not forecast coverage or ground truth. Run only on trusted local event
pickles produced by causal_events.py.
"""

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.optimize import minimize
from threadpoolctl import threadpool_limits

from causal_prepare import HOLIDAYS, original_alighting
from contracts import sha256

SEOUL = 'Asia/Seoul'


def classify(events):
    local = events.t_depart.dt.tz_convert(SEOUL)
    minute = local.dt.hour * 60 + local.dt.minute
    day = local.dt.strftime('%Y-%m-%d')
    holiday = day.isin(HOLIDAYS)
    work = (local.dt.day_of_week < 5) & ~holiday

    cell = pd.Series('work-night', index=events.index)
    cell.loc[work & minute.between(360, 599)] = 'work-morning'
    cell.loc[work & minute.between(600, 1019)] = 'work-day'
    cell.loc[work & minute.between(1020, 1259)] = 'work-evening'
    daytime = minute.between(360, 1259)
    cell.loc[~work & ~holiday & daytime] = 'weekend-day'
    cell.loc[~work & ~holiday & ~daytime] = 'weekend-night'
    cell.loc[holiday & daytime] = 'holiday-day'
    cell.loc[holiday & ~daytime] = 'holiday-night'

    return cell, day


def eligible_events(events, cutoff):
    return events.capacity.between(30, 70) & events.gap.between(.2, 60) & events.t_known.lt(cutoff)


def count_cells(events, cutoff):
    cell, day = classify(events)
    eligible = eligible_events(events, cutoff)
    result = {}
    for name, rows in events.groupby(cell, sort=True):
        subset = rows.loc[eligible]
        result[name] = dict(events=len(rows), eligible_events=len(subset),
                            eligible_days=day.loc[subset.index].nunique(),
                            eligible_stops=subset.stop.nunique())
    return result


def inventory(source, periods):
    result = {}
    for path in sorted(source.glob('events_*.pkl')):
        events = pd.read_pickle(path)
        windows = {}
        for name, (start, end) in periods.items():
            first = pd.Timestamp(start, tz=SEOUL)
            cutoff = pd.Timestamp(end, tz=SEOUL)
            if not first < cutoff:
                raise ValueError(f'Expected start before exclusive end: {name}')
            windows[name] = count_cells(events.loc[events.t_depart.ge(first) & events.t_depart.lt(cutoff)], cutoff)
        result[path.stem.removeprefix('events_')] = dict(source_sha256=sha256(path), periods=windows)
    if not result:
        raise ValueError('No route event files')
    return dict(time_zone=SEOUL, periods=periods, routes=result)


def pilot_partitions(events, periods):
    fit_start, fit_end = (pd.Timestamp(day, tz=SEOUL) for day in periods['fit'])
    test_start, test_end = (pd.Timestamp(day, tz=SEOUL) for day in periods['holdout'])
    if not fit_start < fit_end <= test_start < test_end:
        raise ValueError('Fit and holdout must be ordered and cannot overlap')

    partitions = []
    for start, end in ((fit_start, fit_end), (test_start, test_end)):
        rows = events.loc[events.t_depart.ge(start) & events.t_depart.lt(end) & eligible_events(events, end)]
        cell, _ = classify(rows)
        partitions.append({name: group for name, group in rows.groupby(cell, sort=True)
                           if name not in ('work-morning', 'work-evening')})
    return tuple(partitions)


def spaced_sample(rows, limit):
    ordered = rows.sort_values(['t_known', 'closure_id'])
    return ordered.iloc[np.linspace(0, len(ordered) - 1, min(limit, len(ordered)), dtype=int)]


def fit_pilot(events, periods, functions):
    fit, holdout = pilot_partitions(events, periods)
    # Eight three-hour bins span a day. Each fit uses only rows from its own cell.
    functions['BIN_MINUTES'] = 180
    functions['BANDS']['extended'] = (0, 1440)
    result = {}
    for name in sorted(fit.keys() | holdout.keys()):
        train_rows = fit.get(name, events.iloc[:0])
        test_rows = holdout.get(name, events.iloc[:0])
        item = dict(fit_eligible=len(train_rows), holdout_eligible=len(test_rows))
        result[name] = item
        if len(train_rows) < 80:
            item['status'] = 'INSUFFICIENT_PILOT_SAMPLE'
            continue
        train = spaced_sample(train_rows, 300)
        data = functions['prepare'](train, 'extended')
        rate = max(np.maximum(data['arrive'] - data['depart'], 0).mean() / max(data['gap'].mean(), 1), .05)
        initial = np.r_[np.full(8, np.log(rate)), -3., 0., 1.]
        with threadpool_limits(limits=1):
            fit_result = minimize(functions['negative_log_likelihood'], initial, args=(data,),
                                  method='L-BFGS-B', bounds=[(-8, 4)] * 8 + [(-9, 6), (-6, 4), (-4, 6)],
                                  options={'maxiter': 100})
            if not fit_result.success or not np.isfinite(fit_result.x).all():
                item.update(status='FIT_FAILED', reason=str(fit_result.message))
                continue
        item.update(status='PILOT_ONLY', fit_rows=len(train), theta=fit_result.x.tolist(),
                    fit_max_known=train.t_known.max().isoformat())
        if test_rows.empty:
            continue
        test = spaced_sample(test_rows, 1000)
        observed = functions['prepare'](test, 'extended')
        predicted = functions['predict'](fit_result.x, observed)
        item.update(holdout_rows=len(test),
                    holdout_min_departure=test.t_depart.min().isoformat(),
                    observed_departure_mae=float(np.abs(predicted['expected_depart'] - observed['depart']).mean()),
                    last_arrival_departure_mae=float(np.abs(observed['arrive'] - observed['depart']).mean()),
                    observed_departure_log_loss=float(-predicted['log_lik'].mean()))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--period', nargs=3, action='append', required=True, metavar=('NAME', 'START', 'END_EXCLUSIVE'))
    parser.add_argument('--pilot-lab', type=Path, help='Trusted alight_fit.py for a non-serving experiment')
    parser.add_argument('--pilot-route', action='append')
    args = parser.parse_args()
    periods = {name: (start, end) for name, start, end in args.period}
    if len(periods) != len(args.period):
        parser.error('Period names must be unique')
    if args.pilot_lab:
        if not args.pilot_route or periods.keys() != {'fit', 'holdout'}:
            parser.error('Pilot requires explicit routes and fit and holdout periods')
        functions = original_alighting(args.pilot_lab)
        routes = {}
        for route in args.pilot_route:
            path = args.source / f'events_{route}.pkl'
            routes[route] = dict(source_sha256=sha256(path),
                                 cells=fit_pilot(pd.read_pickle(path), periods, functions))
        print(json.dumps(dict(periods=periods, lab_sha256=sha256(args.pilot_lab), routes=routes), indent=2))
        return
    print(json.dumps(inventory(args.source, periods), indent=2))


if __name__ == '__main__':
    main()
