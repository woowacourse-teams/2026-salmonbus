import unittest

import pandas as pd

from extended_calibration import pilot_partitions


class ExtendedCalibrationFitTest(unittest.TestCase):
    def test_fit_excludes_rush_and_closure_witnesses_from_later_periods(self):
        stamps = pd.to_datetime(['2026-10-21 09:59', '2026-10-21 10:00',
                                 '2026-10-21 23:59', '2026-10-31 00:00']).tz_localize('Asia/Seoul')
        events = pd.DataFrame(dict(t_depart=stamps, t_known=stamps + pd.Timedelta(minutes=1),
                                   capacity=45, gap=5, stop=2))
        periods = {
            'fit': ('2026-10-01', '2026-10-22'),
            'holdout': ('2026-10-31', '2026-11-05'),
        }
        fit, holdout = pilot_partitions(events, periods)
        self.assertEqual({'work-day': [1]}, {key: list(rows.index) for key, rows in fit.items()})
        self.assertEqual({'weekend-night': [3]}, {key: list(rows.index) for key, rows in holdout.items()})

    def test_overlapping_holdout_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'overlap'):
            pilot_partitions(pd.DataFrame(), {
                'fit': ('2026-10-01', '2026-10-22'),
                'holdout': ('2026-10-21', '2026-11-05'),
            })
