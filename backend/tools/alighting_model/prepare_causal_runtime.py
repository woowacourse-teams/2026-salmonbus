"""Declare the confirmed-time runtime contract without changing learned weights."""
import argparse
import json
from pathlib import Path
import shutil
from contracts import sha256

PROFILE = 'alighting-confirmed-closure-v2'


def prepare(source, output):
    manifest = json.loads((source/'manifest.json').read_text())
    if output.exists() or manifest['serving_ready']:
        raise ValueError('New output and inactive research source required')
    for name, digest in manifest['files'].items():
        if sha256(source/name) != digest:
            raise ValueError('Source changed: '+name)
    shutil.copytree(source, output)
    for path in output.glob('statistics-*.json'):
        if path.name == 'statistics-manifest.json':
            continue
        payload = json.loads(path.read_text());payload['input_profile'] = PROFILE
        path.write_text(json.dumps(payload, allow_nan=False))
    contract = json.loads((output/'feature-contract.json').read_text())
    contract.update(input_profile=PROFILE, label_version='alighting-confirmed-routeband-v2',
                    capacity_policy='observed-vehicle-prefix-max-through-closure-v2',
                    online_feature_parity_verified=False)
    (output/'feature-contract.json').write_text(json.dumps(contract,indent=2))
    manifest.update(parent_manifest_sha256=sha256(source/'manifest.json'),
                    input_profile=PROFILE, serving_ready=False,
                    files={p.name:sha256(p) for p in sorted(output.iterdir()) if p.name!='manifest.json'})
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2))
    for name in ['base.json','gate.json','pool.json','constants.json']:
        assert sha256(source/name)==sha256(output/name)
    print(json.dumps(dict(manifest_sha256=sha256(output/'manifest.json'),weights_unchanged=True)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();prepare(a.source,a.output)
