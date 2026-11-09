import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contracts import FULL80, SEAT88, SEAT_COLUMNS, TARGET_STATS, ENTRY, gate_base, pool_design, seat_design
from refit_pool import compare_predictions


class ContractTests(unittest.TestCase):
    def test_feature_position_contract(self):
        self.assertEqual((len(SEAT88), len(FULL80)), (88, 80))
        self.assertEqual(SEAT88[81:84], TARGET_STATS)
        self.assertEqual(SEAT88[84:], ENTRY)
        self.assertEqual(FULL80[50:56], ['q_last_full', 'q_full_since', 'q_since_clear', 'q_unknown', 'q_ahead_n', 'q_ahead_seats'])
        self.assertEqual(FULL80[-1], 'h')

    def test_seat_rounding_and_probability_upcast_match_original(self):
        frame = pd.DataFrame({n: [1.123456789, 2.123456789] for n in SEAT_COLUMNS + TARGET_STATS + ENTRY})
        frame['h'] = np.array([1, 12], dtype='int64')
        x = np.full((2, 79), np.float32(.123456789), dtype='float32')
        rows = np.array([1, 0])
        seats = seat_design(frame, x, rows)
        full = pool_design(frame, x, rows)
        self.assertEqual(seats.dtype, np.float32)
        self.assertEqual(full.dtype, np.float64)
        self.assertEqual(full[0, 0], float(np.float32(.123456789)))
        self.assertEqual(seats[0, 50], np.float32(2.123456789))
        np.testing.assert_array_equal(full[:, -1], [12, 1])

    def test_gate_clips_base_below_zero_but_not_above70_before_mix(self):
        # Synthetic cases: pre-clipping base to 70 would incorrectly return 35.
        predicted = gate_base(np.array([-10., 100., 200.]), np.array([[0, .5, .5], [.5, 0, .5], [0, 0, 1]]), 1.3)
        np.testing.assert_allclose(predicted, [.65, 50., 70.], rtol=0, atol=1e-15)

    def test_nan_is_preserved_for_tree_missing_branches(self):
        frame = pd.DataFrame({n: [np.nan] for n in SEAT_COLUMNS + TARGET_STATS + ENTRY})
        frame['h'] = np.array([2], dtype='int64')
        x = np.zeros((1, 79), dtype='float32')
        self.assertTrue(np.isnan(seat_design(frame, x, np.array([0]))[0, 50]))

    def test_saved_prediction_mismatch_is_rejected(self):
        frame = pd.DataFrame(dict(h=[1, 1], pool_gb=[.2, .8], is_keep=[True, False], is_test=[False, True], label=[1, 0]))
        class ChangedModel:
            def predict_proba(self, values):
                return np.array([[.8, .2], [.1, .9]])[:len(values)]
        with self.assertRaisesRegex(ValueError, 'disagrees'):
            compare_predictions(ChangedModel(), frame, np.zeros((2, 79), dtype='float32'))

    def test_non_finite_predictions_are_rejected(self):
        frame = pd.DataFrame(dict(h=[1], pool_gb=[.2], is_keep=[False], is_test=[True], label=[0]))
        class BadModel:
            def predict_proba(self, values):
                return np.array([[np.nan, np.nan]])
        with self.assertRaisesRegex(ValueError, 'Non-finite'):
            compare_predictions(BadModel(), frame, np.zeros((1, 79), dtype='float32'))


if __name__ == '__main__':
    unittest.main()
