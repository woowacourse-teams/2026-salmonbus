"""Bind confirmed-time releases to existing local validation reports.

Passing these checks records the tested scope. It does not assert production
load, original quality assignments, or end-to-end bootstrap were measured.
"""
import math

PROFILE = 'les-confirmed-closure-v2'
LABEL = 'les-confirmed-routeband-v2'
CAPACITY = 'observed-vehicle-prefix-max-through-closure-v2'
CONTRACT = dict(input_profile=PROFILE, label_version=LABEL, capacity_policy=CAPACITY)


def require_profile(value):
    if any(value.get(key) != expected for key, expected in CONTRACT.items()):
        raise ValueError('confirmed input/label/capacity contract mismatch')


def checked_routes(contents):
    import json
    contract = json.loads(contents.get('feature-contract.json', b'{}'))
    if contract.get('input_profile') != PROFILE:
        return None
    require_profile(contract)
    routes = json.loads(contents['statistics-manifest.json'])['routes']
    if not routes or len(routes) != len(set(routes)):
        raise ValueError('empty or duplicate confirmed routes')
    for route in routes:
        if json.loads(contents[f'statistics-{route}.json']).get('input_profile') != PROFILE:
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
        if result.get('routes') != routes or result.get('input_profile') != PROFILE:
            raise ValueError('runtime route/profile mismatch')
        if result.get('mode') != 'candidate' or result.get('verified_cases') != len(routes):
            raise ValueError('missing candidate runtime checks')
        return len(routes)
    if check not in ('historical_replay', 'settlement'):
        raise ValueError('unknown verification')
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
    if evidence.get('schema') != 'les-confirmed-serving-evidence-v1':
        raise ValueError('wrong evidence schema')
    if receipt.get('routes') != routes or evidence.get('routes') != routes:
        raise ValueError('receipt route coverage mismatch')
    cases = verify_result(check, evidence['result'], source_digest, routes)
    if receipt['verified_cases'] != cases or evidence['verified_cases'] != cases:
        raise ValueError('receipt case count mismatch')
