"""Candidate choice must not tune against the held-back period."""
import sys
import unittest
from pathlib import Path

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from optimize_combination import blend_experts, metrics, select_candidate


class CombinationSelectionTest(unittest.TestCase):
    def test_check_period_cannot_choose_candidate(self):
        scores = {
            'baseline': {'selection': {'mae': 3.0}, 'check': {'mae': 1.0}},
            'candidate': {'selection': {'mae': 2.0}, 'check': {'mae': 9.0}},
        }
        self.assertEqual(select_candidate(scores), 'candidate')

    def test_equal_mae_keeps_baseline(self):
        scores = {name: {'selection': {'mae': 2.0}} for name in ['baseline', 'candidate']}
        self.assertEqual(select_candidate(scores), 'baseline')

    def test_mae_weights_each_forecast_equally(self):
        result = metrics(np.array([0.0, 2.0, 10.0]), np.array([0.0, 0.0, 4.0]))
        self.assertAlmostEqual(result['mae'], 8 / 3)
        self.assertEqual(result['rows'], 3)
        self.assertEqual(result['over_by3_low'], 0.0)

    def test_tree_composition_preserves_weighted_predictions_and_originals(self):
        x = np.arange(60, dtype='float32').reshape(-1, 1)
        y = np.sin(x[:, 0]) + x[:, 0] / 3
        squared = HistGradientBoostingRegressor(max_iter=3, min_samples_leaf=4,
                                               early_stopping=False).fit(x, y)
        absolute = HistGradientBoostingRegressor(loss='absolute_error', max_iter=4,
                                                min_samples_leaf=4, early_stopping=False).fit(x, y)
        before = squared.predict(x).copy()
        for weight in [0.25, 0.5, 0.75]:
            model = blend_experts(squared, absolute, weight)
            expected = (1 - weight) * before + weight * absolute.predict(x)
            np.testing.assert_allclose(model.predict(x), expected, atol=1e-12, rtol=0)
        np.testing.assert_array_equal(squared.predict(x), before)


if __name__ == '__main__':
    unittest.main()
