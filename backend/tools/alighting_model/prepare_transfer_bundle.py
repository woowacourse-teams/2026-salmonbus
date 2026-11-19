"""Package a verified parent, an additive data candidate and the evidence for each new route."""
import argparse
import json
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory

from confirmed_serving_verification import verify_result
from contracts import sha256

CELLS = {'work-morning', 'work-day', 'work-evening', 'work-night',
         'weekend-day', 'weekend-night', 'holiday-day', 'holiday-night'}


def read(path):
    return json.loads(path.read_bytes())


def collect(reports, additions, digest):
    routes = {route: {} for route in additions}
    revisions = set()
    for path in reports:
        report = read(path)
        row, = report['routes']
        route = row['route']
        verify_result('settlement', report, digest, [route])
        field, = row['extended_hours']
        cell = field['cell']
        if route not in routes or cell not in CELLS or cell in routes[route]:
            raise ValueError(f'Unexpected or duplicate evidence: {route}/{cell}')
        if row['trace']['kind'] not in ('field', 'clock_replay'):
            raise ValueError(f'Missing field or clock-replay provenance: {route}/{cell}')
        revisions.add(report['verification_code_sha'])
        routes[route][cell] = report
    if len(revisions) != 1 or any(set(cells) != CELLS for cells in routes.values()):
        raise ValueError('Mixed replay code or incomplete time-cell coverage')
    return routes, revisions.pop()


def copy_declared(source, destination):
    destination.mkdir()
    manifest = read(source / 'manifest.json')
    for name, expected in manifest['files'].items():
        path = source / name
        if Path(name).name != name or path.is_symlink() or sha256(path) != expected:
            raise ValueError('Invalid bundle input: ' + name)
        shutil.copyfile(path, destination / name)
    shutil.copyfile(source / 'manifest.json', destination / 'manifest.json')


def prepare(base, candidate, numeric_path, runtime_path, reports, output):
    if output.exists():
        raise ValueError('Choose a new output directory')
    parent_manifest = base / 'manifest.json'
    source_manifest = candidate / 'manifest.json'
    digest = sha256(source_manifest)
    parent = read(parent_manifest)
    source = read(source_manifest)
    if (parent.get('serving_ready') is not True or source.get('serving_ready') is not False
            or source.get('parent_manifest_sha256') != sha256(parent_manifest)):
        raise ValueError('Expected the actual serving parent and its inactive candidate')
    original = set(read(base / 'statistics-manifest.json')['routes'])
    names = read(candidate / 'statistics-manifest.json')['routes']
    if not original < set(names):
        raise ValueError('Candidate must only add routes')
    additions = set(names) - original
    numeric, runtime = read(numeric_path), read(runtime_path)
    verify_result('feature_parity', numeric, digest, names)
    verify_result('runtime_validation', runtime, digest, names)
    routes, revision = collect(reports, additions, digest)
    evidence = dict(schema='alighting-transfer-evidence-v1', candidate_sha256=digest,
                    verification_code_sha=revision, numeric=numeric, runtime=runtime, routes=routes)
    with TemporaryDirectory(dir=output.parent) as tmp:
        stage = Path(tmp) / 'release'
        stage.mkdir()
        copy_declared(base, stage / 'base')
        copy_declared(candidate, stage / 'candidate')
        proofs = stage / 'transfer-evidence.json'
        proofs.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        release = dict(schema='alighting-transfer-serving-v1', serving_ready=True,
                       release_id=runtime['release_id'], data_through=runtime['data_through'],
                       candidate_sha256=digest, base_sha256=sha256(parent_manifest), evidence_sha256=sha256(proofs))
        (stage / 'manifest.json').write_text(json.dumps(release, indent=2) + '\n')
        stage.rename(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('base', 'candidate', 'numeric', 'runtime', 'output'):
        parser.add_argument('--' + key, type=Path, required=True)
    parser.add_argument('--worker', type=Path, action='append', required=True)
    opt = parser.parse_args()
    prepare(opt.base, opt.candidate, opt.numeric, opt.runtime, opt.worker, opt.output)
