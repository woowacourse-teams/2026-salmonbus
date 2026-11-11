"""Bind confirmed-time releases to existing local validation reports.

Passing these checks records the tested scope. It does not assert production
load, original quality assignments, or end-to-end bootstrap were measured.
"""
import math
import re

PROFILE = 'alighting-confirmed-closure-v2'
LABEL = 'alighting-confirmed-routeband-v2'
CAPACITY = 'observed-vehicle-prefix-max-through-closure-v2'
CONTRACT = dict(input_profile=PROFILE, label_version=LABEL, capacity_policy=CAPACITY)



def canonical_identifier(value):
    """Interpret historical contract identifiers without altering source bytes."""
    return 'alighting-' + value[4:] if isinstance(value, str) and value.startswith('les-') else value

def require_profile(value):
    if any(canonical_identifier(value.get(key)) != expected for key, expected in CONTRACT.items()):
        raise ValueError('confirmed input/label/capacity contract mismatch')


def checked_routes(contents):
    import json
    contract = json.loads(contents.get('feature-contract.json', b'{}'))
    if canonical_identifier(contract.get('input_profile')) != PROFILE:
        return None
    require_profile(contract)
    routes = json.loads(contents['statistics-manifest.json'])['routes']
    if not routes or len(routes) != len(set(routes)):
        raise ValueError('empty or duplicate confirmed routes')
    for route in routes:
        if canonical_identifier(json.loads(contents[f'statistics-{route}.json']).get('input_profile')) != PROFILE:
            raise ValueError('mixed statistics profile: '+route)
    return routes


def within(value, tolerance):
    return type(value) in (float, int) and math.isfinite(value) and 0 <= value <= tolerance


def verify_result(check, result, source_digest, routes):
    if result.get('passed') is not True or result.get('bundle_manifest_sha256') != source_digest:
        raise ValueError('failed validation or different source model: '+check)
    if check == 'feature_parity':
        cases = 0
        for name in ['golden.json', 'boundaries.json']:
            row = result[name]
            differences = row['max_absolute_difference']
            if type(row['rows']) is not int or row['rows'] < 1 or len(differences) != 6:
                raise ValueError('missing numeric cases')
            if not all(within(value, 1e-9) for value in differences):
                raise ValueError('numeric difference exceeds tolerance')
            cases += row['rows']
        return cases
    if check == 'runtime_validation':
        if (result.get('routes') != routes or result.get('input_profile') != PROFILE
                or result.get('runtime_calculation_version') != 'alighting-online-capacity-snapshot-v2'):
            raise ValueError('runtime route/profile mismatch')
        if result.get('mode') != 'candidate' or result.get('verified_cases') != len(routes):
            raise ValueError('missing candidate runtime checks')
        return len(routes)
    if check not in ('historical_replay', 'settlement'):
        raise ValueError('unknown verification')
    if (result.get('runtime_capacity_policy') != 'vehicle-capacity-snapshot-v1'
            or result.get('runtime_label_version') != 'alighting-confirmed-capacity-snapshot-v3'
            or result.get('runtime_calculation_version') != 'alighting-online-capacity-snapshot-v2'):
        raise ValueError('runtime capacity snapshot contract mismatch')
    targets_per_forecast = 2
    if 'settlement_scope' in result:
        if result['settlement_scope'] != 'after_alighting_only':
            raise ValueError('unknown settlement scope')
        targets_per_forecast = 1
    rows = result['routes']
    if [row['route'] for row in rows] != routes:
        raise ValueError('Worker validation does not cover source routes')
    cases = 0
    for row in rows:
        if row.get('passed') is not True or type(row['cases']) is not int or row['cases'] < 1:
            raise ValueError('failed Worker route')
        if row['settled_targets'] != row['cases'] * targets_per_forecast or row['failures'] != []:
            raise ValueError('missing settlement or evidence mismatch')
        for field, tolerance in [('max_feature_difference', 0), ('max_prediction_difference', 1e-9),
                                 ('max_label_difference', 1e-8)]:
            if not within(row[field], tolerance):
                raise ValueError('Worker difference exceeds tolerance: '+field)
        cases += row['cases']
    return cases * (targets_per_forecast if check == 'settlement' else 1)


def verify_receipt(check, receipt, evidence, source_digest, routes):
    require_profile(receipt)
    if canonical_identifier(evidence.get('schema')) != 'alighting-confirmed-serving-evidence-v1':
        raise ValueError('wrong evidence schema')
    if receipt.get('routes') != routes or evidence.get('routes') != routes:
        raise ValueError('receipt route coverage mismatch')
    cases = verify_result(check, evidence['result'], source_digest, routes)
    if receipt['verified_cases'] != cases or evidence['verified_cases'] != cases:
        raise ValueError('receipt case count mismatch')


EXTENDED_CELLS = ('work-morning', 'work-day', 'work-evening', 'work-night',
                  'weekend-day', 'weekend-night', 'holiday-day', 'holiday-night')


def verify_extended(result, source_digest, code_sha, routes):
    if (not isinstance(code_sha, str) or not re.fullmatch(r'[0-9a-f]{40}', code_sha)
            or result.get('bundle_manifest_sha256') != source_digest
            or result.get('verification_code_sha') != code_sha):
        raise ValueError('extended verification source or code mismatch')
    rows = result.get('extended_hours')
    expected = {(route, cell) for route in routes for cell in EXTENDED_CELLS}
    if not isinstance(rows, list):
        raise ValueError('extended verification rows are missing')
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('invalid extended verification row')
        key = row.get('route'), row.get('cell')
        if key not in expected:
            raise ValueError('unexpected or duplicate extended verification row')
        expected.remove(key)
        published, settled = row.get('published'), row.get('settled')
        if type(published) is not int or type(settled) is not int or not 0 < settled <= published:
            raise ValueError('missing extended publication or settlement evidence')
    if expected:
        raise ValueError('missing extended route or time cell')
