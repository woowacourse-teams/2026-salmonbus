import unittest

import numpy as np
import pandas as pd
from scipy.stats import binom, nbinom

from extended_forecast import PERIODS, departure_partition, forecast_partitions, join_origins


def capacity_change(arrive=10, depart=5):
    arrival = pd.Timestamp('2026-10-31T10:00:00+09:00')
    events = pd.DataFrame(dict(
        trip=[1], chain=[2], stop=[10], closure_id=[7], t_arrive=[arrival],
        t_depart=[arrival + pd.Timedelta(seconds=20)], t_known=[arrival + pd.Timedelta(minutes=1)],
        arrive=[arrive], depart=[depart], capacity=[45], onboard=[45 - arrive], gap=[5],
    ))
    origins = pd.DataFrame(dict(
        observation_id=[1, 2], trip=[1, 1], chain=[2, 2], passed=[8, 9],
        seats=[8, 8], capacity=[44, 45],
        received=[arrival - pd.Timedelta(seconds=30), arrival - pd.Timedelta(seconds=15)],
    ))
    return origins, events


def exact_departure_components(theta, rows):
    onboard = rows.onboard.to_numpy()
    alight = np.arange(onboard.max() + 1)
    mean = np.exp(theta[0]) * rows.gap.to_numpy() + .001
    dispersion = np.exp(theta[10])
    boarded = (rows.arrive - rows.depart).to_numpy()[:, None] + alight
    weights = binom.pmf(alight, onboard[:, None], 1 / (1 + np.exp(-theta[8])))
    likelihood = nbinom.pmf(boarded, dispersion, dispersion / (dispersion + mean[:, None]))
    return weights, likelihood, None, None


def seat_holdout(origins, events):
    calibration = {'weekend-day': dict(status='PILOT_ONLY',
                                       theta=np.r_[np.full(8, np.log(2)), np.log(.2 / .8), 0, np.log(2)])}
    functions = dict(BANDS={}, prepare=lambda rows, band: rows, components=exact_departure_components)
    return forecast_partitions(origins, events, calibration, functions)['holdout']


class ExtendedForecastTest(unittest.TestCase):
    def test_extended_origin_is_labeled_with_rush_stop_parameters(self):
        for origin, passage, cell, bin_start in (
            ('05:57', '06:02', 'work-night', 360),
            ('16:58', '17:03', 'work-day', 1020),
        ):
            with self.subTest(origin=origin):
                obs, events = capacity_change()
                obs['received'] = pd.Timestamp(f'2026-11-02T{origin}:00+09:00')
                events['t_depart'] = pd.Timestamp(f'2026-11-02T{passage}:00+09:00')
                events['t_arrive'] = events.t_depart - pd.Timedelta(seconds=20)
                events['t_known'] = events.t_depart + pd.Timedelta(seconds=20)
                band = 'morning' if bin_start == 360 else 'evening'
                rush = {(10, band): np.r_[np.full(8, np.log(2)), np.log(.2 / .8), 0, np.log(2)]}
                functions = dict(BANDS={}, prepare=lambda rows, name: rows, components=exact_departure_components)
                rows = forecast_partitions(obs, events, {}, functions, rush)['holdout'].set_index('observation_id')
                self.assertEqual(cell, rows.loc[1, 'cell'])
                self.assertAlmostEqual(16.27113304835795, rows.loc[1, 'label'], places=12)

    def test_posterior_reads_passage_minute_and_keeps_origin_minute_as_feature(self):
        obs, events = capacity_change()
        events['t_depart'] = pd.Timestamp('2026-10-31T12:01:00+09:00')
        events['t_arrive'] = events.t_depart - pd.Timedelta(seconds=20)
        events['t_known'] = events.t_depart + pd.Timedelta(seconds=20)
        obs['received'] = pd.Timestamp('2026-10-31T11:58:00+09:00')
        calibration = {'weekend-day': dict(status='PILOT_ONLY', theta=np.zeros(11))}
        def prepare(rows, band):
            self.assertEqual({721}, set(rows.minute))
            return rows

        functions = dict(BANDS={}, prepare=prepare, components=exact_departure_components)
        rows = forecast_partitions(obs, events, calibration, functions)['holdout']
        self.assertEqual({718}, set(rows.minute))

    def test_origin_is_strictly_before_arrival_and_in_same_chain(self):
        arrival = pd.Timestamp('2026-10-31T10:00:00+09:00')
        targets = pd.DataFrame(dict(trip=[1], chain=[2], stop=[10], t_arrive=[arrival]))
        origins = pd.DataFrame(dict(
            observation_id=[1, 2, 3, 4], trip=[1, 1, 1, 1], chain=[2, 2, 3, 2],
            passed=[9, 9, 9, 9], seats=[8, 7, 6, 5], capacity=[45, 45, 45, 45],
            received=[arrival - pd.Timedelta(seconds=5), arrival,
                      arrival - pd.Timedelta(seconds=1), arrival + pd.Timedelta(seconds=1)],
        ))
        joined = join_origins(targets, origins)
        self.assertEqual([1], joined.observation_id.tolist())

    def test_seat_label_uses_each_origin_capacity_and_matches_java_settlement(self):
        origins, events = capacity_change()
        rows = seat_holdout(origins, events).set_index('observation_id')

        self.assertAlmostEqual(16.27113304835795, rows.loc[1, 'label'], places=12)
        self.assertAlmostEqual(16.449546403813994, rows.loc[2, 'label'], places=12)

    def test_seat_label_excludes_arrival_or_departure_above_origin_capacity(self):
        for arrive, depart in ((45, 5), (10, 45)):
            with self.subTest(arrive=arrive, depart=depart):
                origins, events = capacity_change(arrive=arrive, depart=depart)
                self.assertEqual([2], seat_holdout(origins, events).observation_id.tolist())

    def test_zero_departure_remains_eligible_when_arrival_exceeds_origin_capacity(self):
        origins, events = capacity_change(arrive=45, depart=0)
        rows = departure_partition(origins, events, PERIODS['holdout'])
        self.assertEqual({1: 0, 2: 0}, rows.set_index('observation_id').depart.to_dict())

    def test_departure_probability_uses_observed_departure_without_posterior_fields(self):
        arrival = pd.Timestamp('2026-10-31T10:00:00+09:00')
        targets = pd.DataFrame(dict(trip=[1], chain=[2], stop=[10], t_arrive=[arrival],
                                    t_known=[arrival + pd.Timedelta(minutes=1)], depart=[0], closure_id=[7]))
        origins = pd.DataFrame(dict(
            observation_id=[1], trip=[1], chain=[2], passed=[9], seats=[8], capacity=[45],
            received=[arrival - pd.Timedelta(seconds=5)],
        ))
        rows = departure_partition(origins, targets, ('2026-10-31', '2026-11-05'))
        self.assertEqual([(7, 0, 45, 599, True)],
                         list(rows[['closure_id', 'depart', 'capacity', 'minute', 'off_day']].itertuples(
                             index=False, name=None)))
