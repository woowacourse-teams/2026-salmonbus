"""Synthetic rejection cases, never production validation evidence."""
import copy
import unittest

from confirmed_serving_verification import PROFILE, verify_result


class ConfirmedVerificationTest(unittest.TestCase):
    def setUp(self):
        self.routes = ['3330', 'G8110']
        self.digest = 'b' * 64
        self.worker = dict(passed=True, bundle_manifest_sha256=self.digest, routes=[
            dict(route=route, passed=True, cases=1, settled_targets=2, failures=[],
                 max_feature_difference=0, max_prediction_difference=0, max_label_difference=0)
            for route in self.routes])

    def test_complete_reports_count_both_settlement_targets(self):
        self.assertEqual(verify_result('historical_replay', self.worker, self.digest, self.routes), 2)
        self.assertEqual(verify_result('settlement', self.worker, self.digest, self.routes), 4)

    def test_after_alighting_only_reports_count_one_settlement_per_forecast(self):
        self.worker['settlement_scope'] = 'after_alighting_only'
        for row in self.worker['routes']:
            row['settled_targets'] = 1
        self.assertEqual(verify_result('settlement', self.worker, self.digest, self.routes), 2)

    def test_missing_or_duplicate_route_is_rejected(self):
        for rows in [self.worker['routes'][:1], [self.worker['routes'][0]] * 2]:
            with self.subTest(rows=rows):
                result = copy.deepcopy(self.worker)
                result['routes'] = rows
                with self.assertRaises(ValueError):
                    verify_result('settlement', result, self.digest, self.routes)

    def test_pass_flag_cannot_hide_failed_numeric_or_settlement_results(self):
        for field, value in [('settled_targets', 1), ('failures', [{'post_id': 'mismatch'}]),
                             ('max_feature_difference', .01), ('max_prediction_difference', .01),
                             ('max_label_difference', .01), ('max_label_difference', float('nan'))]:
            with self.subTest(field=field, value=value):
                result = copy.deepcopy(self.worker)
                result['routes'][1][field] = value
                with self.assertRaises(ValueError):
                    verify_result('settlement', result, self.digest, self.routes)

    def test_changed_model_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_result('settlement', self.worker, 'c' * 64, self.routes)

    def test_runtime_must_cover_every_route_and_confirmed_profile(self):
        runtime = dict(passed=True, bundle_manifest_sha256=self.digest, routes=self.routes,
                       input_profile=PROFILE, mode='candidate', verified_cases=2)
        self.assertEqual(verify_result('runtime_validation', runtime, self.digest, self.routes), 2)
        runtime['input_profile'] = 'les-original-v1'
        with self.assertRaises(ValueError):
            verify_result('runtime_validation', runtime, self.digest, self.routes)


if __name__ == '__main__':
    unittest.main()
