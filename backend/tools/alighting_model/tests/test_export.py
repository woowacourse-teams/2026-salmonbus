import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from export_bundle import export_tree_model
from export_statistics import verify_sample_statistics


class ExportTests(unittest.TestCase):
    def test_numeric_export_retains_leaf_scale_and_missing_direction(self):
        dtype = [('feature_idx','i8'), ('num_threshold','f8'), ('missing_go_to_left','?'),
                 ('left','i8'), ('right','i8'), ('value','f8'), ('is_leaf','?'), ('is_categorical','?')]
        nodes = np.array([(0, 2.5, True, 1, 2, 0, False, False),
                          (0, 0, False, 0, 0, .125, True, False),
                          (0, 0, False, 0, 0, -.25, True, False)], dtype=dtype)
        model = SimpleNamespace(is_categorical_=None, _predictors=[[SimpleNamespace(nodes=nodes)]],
                                n_features_in_=1, _baseline_prediction=np.array([[10.]]))
        exported = export_tree_model(model, 'regression')
        self.assertEqual(exported['baseline'], [10.])
        self.assertEqual(exported['trees'][0][0][0], [0, 2.5, True, 1, 2, 0., False])
        self.assertEqual(exported['trees'][0][0][1][5], .125)
        nodes['is_categorical'][0] = True
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            export_tree_model(model, 'regression')

    def test_statistic_check_recovers_integer_sum_from_float32_ratio(self):
        capacity = 68
        frame = pd.DataFrame([dict(target=10, h=5, weekend=0,
            t_pred=pd.Timestamp('2026-10-13 08:00', tz='Asia/Seoul').value,
            p_target_full_rate=0., p_target_mean_dep=0., p_path_net=0.,
            capacity=capacity, q_unknown=0., q_left=.4,
            q_ahead_seats=float(np.float32(20) / np.float32(capacity)),
            q_left_mine=(.4 * capacity + 2 * 5 * 1.2 - 20) / capacity)])
        scope = dict(full_tab={}, dep_tab={}, net_tab={}, lam={}, lam_base={},
                     lam_at=lambda *args: 2., min_per_stop=1.2)
        checked = verify_sample_statistics(frame, scope)
        self.assertLessEqual(checked['max_absolute_difference']['queue_mine'], 1e-10)
        frame.loc[0, 'p_path_net'] = 1.
        with self.assertRaisesRegex(ValueError, 'path_net'):
            verify_sample_statistics(frame, scope)


if __name__ == '__main__':
    unittest.main()
