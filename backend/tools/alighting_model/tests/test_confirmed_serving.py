"""Synthetic rejection cases, never production validation evidence."""
import copy
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from confirmed_serving_verification import EXTENDED_CELLS, PROFILE, checked_routes, verify_extended, verify_result
from prepare_confirmed_receipts import combine_extended


class ConfirmedVerificationTest(unittest.TestCase):
    def setUp(self):
        self.routes = ['3330', 'G8110']
        self.digest = 'b' * 64
        self.worker = dict(runtime_capacity_policy='vehicle-capacity-snapshot-v1',
                           runtime_label_version='alighting-confirmed-capacity-snapshot-v3',
                           runtime_calculation_version='alighting-online-capacity-snapshot-v2', passed=True, bundle_manifest_sha256=self.digest, routes=[
            dict(route=route, passed=True, cases=1, settled_targets=2, failures=[],
                 max_feature_difference=0, max_prediction_difference=0, max_label_difference=0)
            for route in self.routes])

    def test_historical_training_contract_is_read_without_rewriting_bytes(self):
        contract = dict(input_profile='les-confirmed-closure-v2', label_version='les-confirmed-routeband-v2',
                        capacity_policy='observed-vehicle-prefix-max-through-closure-v2')
        contents = {'feature-contract.json': json.dumps(contract).encode(),
                    'statistics-manifest.json': json.dumps(dict(routes=self.routes)).encode()}
        for route in self.routes:
            contents[f'statistics-{route}.json'] = json.dumps(dict(input_profile=contract['input_profile'])).encode()
        original = dict(contents)
        self.assertEqual(checked_routes(contents), self.routes)
        self.assertEqual(contents, original)

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

    def test_retired_runtime_capacity_report_is_rejected(self):
        result = copy.deepcopy(self.worker)
        result['runtime_capacity_policy'] = 'observed-vehicle-prefix-max-through-closure-v2'
        with self.assertRaises(ValueError):
            verify_result('settlement', result, self.digest, self.routes)

    def test_changed_model_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_result('settlement', self.worker, 'c' * 64, self.routes)

    def test_extended_verification_rejects_missing_cells_and_reused_evidence(self):
        code = 'a' * 40
        rows = [dict(route=route, cell=cell, published=2, settled=1)
                for route in self.routes for cell in EXTENDED_CELLS]
        result = dict(bundle_manifest_sha256=self.digest, verification_code_sha=code, extended_hours=rows)
        verify_extended(result, self.digest, code, self.routes)
        for bad in [dict(extended_hours=rows[:-1]), dict(extended_hours=rows + rows[:1]),
                    dict(bundle_manifest_sha256='c' * 64), dict(verification_code_sha='d' * 40)]:
            with self.subTest(bad=list(bad)):
                with self.assertRaises(ValueError):
                    verify_extended(result | bad, self.digest, code, self.routes)

        selected = [row for row in rows if row['route'] != '3330' or not row['cell'].startswith('holiday-')]
        substitution = dict(result, extended_hours=selected, holiday_weekend_substitution_routes=['3330'])
        verify_extended(substitution, self.digest, code, self.routes, ['3330'])
        for bad in [dict(extended_hours=selected[:-1]),
                    dict(extended_hours=[row for row in selected if row['cell'] != 'weekend-day']),
                    dict(extended_hours=rows), dict(holiday_weekend_substitution_routes=['G8110'])]:
            with self.subTest(bad=list(bad)), self.assertRaises(ValueError):
                verify_extended(substitution | bad, self.digest, code, self.routes, ['3330'])

    def test_replay_reports_must_cover_every_cell_and_restore_off_peak_settlement(self):
        code = 'a' * 40
        rows = [dict(route='3330', cell=cell, published=2, settled=1) for cell in EXTENDED_CELLS]
        report = dict(passed=True, bundle_manifest_sha256=self.digest, verification_code_sha=code,
                      routes=[dict(route='3330', passed=True, restored_targets=1, extended_hours=rows)])
        runtime = dict(bundle_manifest_sha256=self.digest)
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'report.json'
            path.write_text(json.dumps(report))
            result = combine_extended(runtime, [path], self.digest, code, ['3330'])
            self.assertEqual(result['extended_hours'], rows)

            report['routes'][0]['restored_targets'] = 0
            path.write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'restore failed'):
                combine_extended(runtime, [path], self.digest, code, ['3330'])

            report['routes'][0]['restored_targets'] = 1
            path.write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'missing extended route or time cell'):
                combine_extended(runtime, [path], self.digest, code, ['3330', '1650'])

    def test_runtime_must_cover_every_route_and_confirmed_profile(self):
        runtime = dict(passed=True, bundle_manifest_sha256=self.digest, routes=self.routes,
                       input_profile=PROFILE, runtime_calculation_version='alighting-online-capacity-snapshot-v2',
                       mode='candidate', verified_cases=2)
        self.assertEqual(verify_result('runtime_validation', runtime, self.digest, self.routes), 2)
        runtime['input_profile'] = 'alighting-original-v1'
        with self.assertRaises(ValueError):
            verify_result('runtime_validation', runtime, self.digest, self.routes)


if __name__ == '__main__':
    unittest.main()
