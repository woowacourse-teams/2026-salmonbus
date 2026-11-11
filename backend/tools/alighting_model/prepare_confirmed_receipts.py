"""Package actual numeric, Worker and runtime reports as source-bound receipts."""
import argparse
import json
from pathlib import Path

from confirmed_serving_verification import CONTRACT, checked_routes, verify_extended, verify_result
from prepare_serving_bundle import CHECKS, digest


def combine_extended(runtime, replays, source_digest, code_sha, routes):
    rows = []
    for path in replays:
        report = json.loads(path.read_bytes())
        if (report.get('passed') is not True or report.get('bundle_manifest_sha256') != source_digest
                or report.get('verification_code_sha') != code_sha):
            raise ValueError(f'Failed replay or different candidate/code: {path.name}')
        for row in report['routes']:
            cells = row['extended_hours']
            off_peak = any(cell['cell'] not in ('work-morning', 'work-evening') for cell in cells)
            if row.get('passed') is not True or (off_peak and row.get('restored_targets', 0) < 1):
                raise ValueError(f'Publication, settlement or restore failed: {path.name}/{row["route"]}')
            if any(cell['route'] != row['route'] for cell in cells):
                raise ValueError(f'Replay row contains results from a different route: {path.name}')
            rows.extend(cells)
    result = dict(runtime, verification_code_sha=code_sha, extended_hours=rows)
    verify_extended(result, source_digest, code_sha, routes)
    return result


def prepare(source, numeric, worker, runtime, output, extended=()):
    if output.exists():
        raise ValueError('new receipt directory required')
    source_bytes = (source/'manifest.json').read_bytes()
    manifest = json.loads(source_bytes)
    if manifest.get('serving_ready') is not False:
        raise ValueError('inactive source required')
    contents = {}
    for name, expected in manifest['files'].items():
        if Path(name).name != name or (source/name).is_symlink():
            raise ValueError('invalid source file')
        contents[name] = (source/name).read_bytes()
        if digest(contents[name]) != expected:
            raise ValueError('changed source file: '+name)
    routes = checked_routes(contents)
    if routes is None:
        raise ValueError('confirmed-time source required')
    source_digest = digest(source_bytes)
    reports = dict(feature_parity=numeric, historical_replay=worker, settlement=worker, runtime_validation=runtime)
    files = {}
    for check in CHECKS:
        result = json.loads(reports[check].read_bytes())
        cases = verify_result(check, result, source_digest, routes)
        if check == 'runtime_validation' and 'extended-hours.json' in contents:
            result = combine_extended(result, extended, source_digest,
                                      manifest.get('extended_verification_code_sha'), routes)
        evidence = json.dumps(dict(schema='alighting-confirmed-serving-evidence-v1', routes=routes,
                                   verified_cases=cases, result=result), ensure_ascii=False, indent=2).encode()
        name = f'evidence-{check}.json'
        files[name] = evidence
        receipt = dict(schema='alighting-serving-verification-v1', check=check, status='PASS',
                       source_manifest_sha256=source_digest, feature_contract_version='alighting-online-v1',
                       verified_cases=cases, evidence_file=name, evidence_sha256=digest(evidence), routes=routes,
                       **CONTRACT)
        files[f'verification-{check}.json'] = json.dumps(receipt, indent=2).encode()
    output.mkdir(parents=True)
    for name, content in files.items():
        (output/name).write_bytes(content)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['source', 'numeric', 'worker', 'runtime', 'output']:
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--extended-worker', type=Path, action='append', default=[])
    args = parser.parse_args()
    prepare(args.source, args.numeric, args.worker, args.runtime, args.output, args.extended_worker)
