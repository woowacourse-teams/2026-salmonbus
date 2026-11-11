import unittest

import pandas as pd

from extended_forecast import departure_partition, join_origins


class ExtendedForecastTest(unittest.TestCase):
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
