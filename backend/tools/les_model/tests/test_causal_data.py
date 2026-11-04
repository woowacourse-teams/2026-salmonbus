"""Synthetic adversarial events; no production data is included."""
import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from causal_source import available_time
from causal_events import build_events
from causal_prepare import frozen_rows
from causal_train import partitions


class CausalDataTest(unittest.TestCase):
    def test_confirmation_must_not_be_missing_or_before_receipt(self):
        received = pd.to_datetime(['2026-10-01T00:00Z'] * 3)
        confirmed = pd.to_datetime(['2026-10-01T00:01Z', None, '2026-09-30T23:59Z'])
        result = available_time(pd.Series(received), pd.Series(confirmed))
        self.assertEqual(result.notna().tolist(), [True, False, False])

    def observations(self, future_seats=60):
        time = pd.date_range('2026-10-01T08:00:00+09:00', periods=5, freq='15s')
        return pd.DataFrame(dict(vid=['v'] * 5, rv=[1] * 5, batch=np.arange(5),
                                 observation_id=np.arange(5), received=time, source_received=time,
                                 stop_order=[2, 2, 3, 3, 4], state=[1, 2, 1, 2, 1],
                                 passed=[1, 2, 2, 3, 3], seats=[30, 29, 29, future_seats, future_seats]))

    def metadata(self):
        return dict(turn=9, stops=[dict(order=i, boardingAllowed=True) for i in range(1, 6)])

    def test_departure_is_not_available_until_next_stop_witness(self):
        o, events = build_events(self.observations(), self.metadata())
        event = events[events.stop == 2].iloc[0]
        self.assertEqual(event.t_depart, o.received.iloc[2])
        self.assertEqual(event.t_known, o.received.iloc[3])
        self.assertEqual(event.closure_id, 3)
        self.assertEqual(event.capacity, 60)

    def test_future_observation_does_not_change_past_capacity(self):
        before, _ = build_events(self.observations(40), self.metadata())
        after, _ = build_events(self.observations(70), self.metadata())
        np.testing.assert_array_equal(before.capacity.iloc[:3], after.capacity.iloc[:3])

    def test_gap_breaks_the_event_instead_of_connecting_across_missing_data(self):
        o = self.observations()
        o.loc[3:, 'received'] += pd.Timedelta(minutes=5)
        o.loc[3:, 'source_received'] += pd.Timedelta(minutes=5)
        with self.assertRaisesRegex(ValueError, 'No closed'):
            build_events(o, self.metadata())

    def test_future_rows_cannot_change_a_closed_event(self):
        original = self.observations(40)
        changed = original.copy()
        changed.loc[4, 'seats'] = 70
        _, before = build_events(original, self.metadata())
        _, after = build_events(changed, self.metadata())
        pd.testing.assert_frame_equal(before[before.stop == 2], after[after.stop == 2])

    def test_frozen_calibration_excludes_boundary_and_future_rows(self):
        events = pd.DataFrame(dict(t_known=pd.to_datetime([
            '2026-10-11T23:59:59+09:00', '2026-10-12T00:00:00+09:00',
            '2026-10-27T00:00:00+09:00']), value=[1, 2, 1000]))
        self.assertEqual(frozen_rows(events).value.tolist(), [1])

    def test_training_and_selection_purge_labels_confirmed_after_their_cut(self):
        frame = pd.DataFrame(dict(date=['2026-10-16', '2026-10-16', '2026-10-21', '2026-10-21'],
                                  label_known=pd.to_datetime(['2026-10-16T23:59:59+09:00',
                                    '2026-10-17T00:00:00+09:00', '2026-10-21T23:59:59+09:00',
                                    '2026-10-22T00:00:00+09:00'])))
        split = partitions(frame)
        self.assertEqual(split['train'].tolist(), [True, False, False, False])
        self.assertEqual(split['dev'].tolist(), [False, False, True, False])


if __name__ == '__main__':
    unittest.main()
