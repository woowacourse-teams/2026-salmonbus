import unittest

import pandas as pd

from extended_calibration import count_cells


class ExtendedCalibrationTest(unittest.TestCase):
    def test_counts_local_day_boundary_and_holiday_without_mixing_rush(self):
        local = pd.to_datetime([
            '2026-11-02 05:59', '2026-11-02 06:00', '2026-11-02 10:00',
            '2026-11-02 17:00', '2026-11-02 21:00', '2026-11-03 00:00',
            '2026-10-31 08:00', '2026-10-09 08:00',
        ]).tz_localize('Asia/Seoul')
        events = pd.DataFrame(dict(t_depart=local.tz_convert('UTC'),
                                   t_known=(local + pd.Timedelta(seconds=30)).tz_convert('UTC'),
                                   capacity=45, gap=8, stop=2))
        counts = count_cells(events, pd.Timestamp('2026-11-04', tz='Asia/Seoul'))
        self.assertEqual({
            'work-night': 3, 'work-morning': 1, 'work-day': 1, 'work-evening': 1,
            'weekend-day': 1, 'holiday-day': 1,
        }, {cell: data['eligible_events'] for cell, data in counts.items()})

    def test_events_closed_after_cutoff_do_not_count_as_eligible(self):
        cutoff = pd.Timestamp('2026-11-03', tz='Asia/Seoul')
        events = pd.DataFrame(dict(t_depart=[cutoff - pd.Timedelta(seconds=30)] * 3,
                                   t_known=[cutoff, cutoff + pd.Timedelta(seconds=1),
                                            cutoff - pd.Timedelta(seconds=1)],
                                   capacity=[45, 45, 45], gap=[8, 8, None], stop=2))
        self.assertEqual({'work-night': {'events': 3, 'eligible_events': 0, 'eligible_days': 0,
                                         'eligible_stops': 0}}, count_cells(events, cutoff))
