#!/usr/bin/env python3
"""Run generated alert expressions against synthetic series with official promtool.
Usage: python3 test_alerts.py /path/to/promtool
Requires PyYAML. Does not contact Grafana or send notifications.
"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

import yaml
from build_alerts import rules

LABELS = {'job': 'salmonbus/worker', 'route_id': '1', 'route_name': '3330'}
COLLECTION = 'salmonbus-diag-collection-age'
FORECAST = 'salmonbus-diag-forecast-pending'


def series(metric, values, per_route=True):
    labels = LABELS if per_route else {'job': 'salmonbus/worker'}
    selector = ','.join(f'{key}="{value}"' for key, value in labels.items())
    return {'series': f'{metric}{{{selector}}}', 'values': values}


def inputs(period, last='1+0x20', commit='1+0x20'):
    return [
        series('salmonbus_collection_expected_interval_seconds', period, False),
        series('salmonbus_collection_last_success_timestamp', last),
        series('salmonbus_collection_first_attempt_timestamp', '1+0x20'),
        series('salmonbus_forecast_last_commit_timestamp', commit),
        series('salmonbus_forecast_pending_age_seconds', '1+0x20'),
        series('salmonbus_forecast_pending_checked_timestamp', '1+60x20'),
        series('salmonbus_collection_usable_rows', '1+0x20'),
    ]


def check(uid, minute, firing=False):
    labels = dict(LABELS, service='salmonbus', severity='critical', prepared_by='SAL-158')
    return {'eval_time': minute if isinstance(minute, str) else f'{minute}m', 'alertname': uid,
            'exp_alerts': [{'exp_labels': labels, 'exp_annotations': {}}] if firing else []}


def run(promtool):
    selected = [r for r in rules if r['rule_uid'] in (COLLECTION, FORECAST)]
    assert len(selected) == 2 and all(r['is_paused'] for r in rules)
    # Prevent stale generated configuration from diverging from tested expressions.
    saved = json.loads((Path(__file__).parent/'alert-rules.paused.json').read_text())
    assert saved == rules
    alert_rules = []
    for r in selected:
        threshold = r['data'][1]['model']['conditions'][0]['evaluator']['params'][0]
        alert_rules.append({'alert': r['rule_uid'],
                            'expr': f"({r['data'][0]['model']['expr']}) > {threshold}",
                            'for': r['for'], 'labels': r['labels']})
    tests = [
        {'name': 'daytime outage fires immediately above 180 seconds and recovers',
         'input_series': inputs('20+0x20', last='1+0x4 300+60x15'),
         'alert_rule_test': [check(COLLECTION, '3m1s'), check(COLLECTION, '3m2s', True),
                             check(COLLECTION, 4, True), check(COLLECTION, 5)]},
        {'name': 'nighttime suppresses collection and forecast warnings',
         'input_series': inputs('600+0x20'),
         'alert_rule_test': [check(COLLECTION, 15), check(FORECAST, 15)]},
        # t=0 is the last 03:59 night sample, t=1 is 04:00.
        {'name': '04:00 transition suppresses old collection; persistent outage eventually fires',
         'input_series': inputs('600 20+0x19'),
         'alert_rule_test': [check(COLLECTION, 1), check(COLLECTION, 9),
                             check(COLLECTION, 10, True), check(COLLECTION, 11, True)]},
        {'name': 'collection resumes before grace expires and never alerts',
         'input_series': inputs('600 20+0x19', last='1+0x4 300+60x15'),
         'alert_rule_test': [check(COLLECTION, 5), check(COLLECTION, 11),
                             check(COLLECTION, 15)]},
        {'name': 'forecast stall with fresh collection respects transition and recovers',
         'input_series': inputs('600 20+0x19', last='1+60x20',
                                commit='1+0x11 720+60x8'),
         'alert_rule_test': [check(FORECAST, 9), check(FORECAST, 10),
                             check(FORECAST, 11, True), check(FORECAST, 12)]},
    ]
    for test in tests:
        test['interval'] = '1m'
    with tempfile.TemporaryDirectory(prefix='salmonbus-alert-test-') as directory:
        root = Path(directory)
        (root/'rules.yaml').write_text(yaml.safe_dump(
            {'groups': [{'name': 'diagnostics', 'rules': alert_rules}]}, sort_keys=False))
        (root/'tests.yaml').write_text(yaml.safe_dump(
            {'rule_files': ['rules.yaml'], 'evaluation_interval': '1s', 'tests': tests},
            sort_keys=False))
        subprocess.run([str(Path(promtool).resolve()), 'test', 'rules', 'tests.yaml'],
                       cwd=root, check=True)


if __name__ == '__main__':
    run(sys.argv[1])
