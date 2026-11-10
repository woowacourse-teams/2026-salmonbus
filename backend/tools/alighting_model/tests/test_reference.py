import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_reference import expected_gate, json_values, select_rows
from contracts import ROUTES


class ReferenceTests(unittest.TestCase):
    def test_reference_joins_candidate_identity_not_position(self):
        frame = pd.DataFrame(dict(route=['3330', '1650'], sample_row=[8, 4]))
        candidate = pd.DataFrame(dict(route=['1650', '3330'], sample_row=[4, 8], seats=[20., 3.]))
        np.testing.assert_array_equal(expected_gate(frame, candidate, [0, 1]), [3., 20.])

    def test_duplicate_or_missing_prediction_is_rejected(self):
        frame = pd.DataFrame(dict(route=['3330'], sample_row=[8]))
        missing = pd.DataFrame(dict(route=['1650'], sample_row=[8], seats=[3.]))
        with self.assertRaisesRegex(ValueError, 'Missing'):
            expected_gate(frame, missing, [0])
        duplicate = pd.DataFrame(dict(route=['3330', '3330'], sample_row=[8, 8], seats=[3., 3.]))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            expected_gate(frame, duplicate, [0])

    def test_missing_feature_encoding_preserves_zero_and_rejects_infinity(self):
        self.assertEqual(json_values(np.array([[0., np.nan, 1.]])), [[0., None, 1.]])
        with self.assertRaisesRegex(ValueError, 'Infinite'):
            json_values(np.array([np.inf]))

    def test_reference_cases_cover_routes_and_low_labels(self):
        frame = pd.DataFrame([dict(route=r, is_test=test, after=v)
                              for r in ROUTES for test, v in [(False, 0), (True, 30), (True, 1), (True, 40)]])
        rows = select_rows(frame, per_route=1)
        chosen = frame.iloc[rows]
        self.assertTrue(chosen.is_test.all())
        self.assertEqual(set(chosen.route), set(ROUTES))
        self.assertEqual(set(chosen.loc[chosen.after <= 2, 'route']), set(ROUTES))


if __name__ == '__main__':
    unittest.main()
