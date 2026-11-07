"""Package actual numeric, Worker and runtime reports as source-bound receipts."""
import argparse
import json
from pathlib import Path

from confirmed_serving_verification import CONTRACT, checked_routes, verify_result
from prepare_serving_bundle import CHECKS, digest


def prepare(source, numeric, worker, runtime, output):
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
        evidence = json.dumps(dict(schema='les-confirmed-serving-evidence-v1', routes=routes,
                                   verified_cases=cases, result=result), ensure_ascii=False, indent=2).encode()
        name = f'evidence-{check}.json'
        files[name] = evidence
        receipt = dict(schema='les-serving-verification-v1', check=check, status='PASS',
                       source_manifest_sha256=source_digest, feature_contract_version='les-online-v1',
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
    args = parser.parse_args()
    prepare(args.source, args.numeric, args.worker, args.runtime, args.output)
