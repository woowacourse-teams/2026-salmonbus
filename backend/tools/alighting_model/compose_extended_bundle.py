"""Make an offline all-hours candidate from the verified release's original files."""

import argparse
import json
import re
import shutil
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory

from contracts import sha256


def read_holidays(calendar):
    dates = json.loads(calendar.read_bytes())
    if not isinstance(dates, list) or not dates:
        raise ValueError('Holiday calendar must list ISO dates')
    if any(not isinstance(value, str) or date.fromisoformat(value).isoformat() != value for value in dates):
        raise ValueError('Holiday calendar contains an invalid ISO date')
    if len(dates) != len(set(dates)):
        raise ValueError('Holiday calendar contains a duplicate date')
    return sorted(dates)


def compose(base, extension, output, code_sha, calendar, substitutions=()):
    if not re.fullmatch(r'[0-9a-f]{40}', code_sha):
        raise ValueError('Expected full verification code SHA')
    if output.exists():
        raise ValueError('Candidate output already exists')
    serving = json.loads((base / 'manifest.json').read_bytes())
    original = base / 'verification-source-manifest.json'
    if sha256(original) != serving['runtime']['source_manifest_sha256']:
        raise ValueError('Original source manifest digest differs')
    manifest = json.loads(original.read_bytes())
    if manifest['serving_ready'] is not False or 'extended-hours.json' in manifest['files']:
        raise ValueError('Expected unmodified rush-only source')
    added = json.loads(extension.read_bytes())
    if ('rush_source_manifest_sha256' in added
            and added['rush_source_manifest_sha256'] != sha256(base / 'manifest.json')):
        raise ValueError('Extension rush settlement source differs from the base bundle')
    if set(added['routes']) != set(serving['runtime']['routes']):
        raise ValueError('Extension must cover every route in the release')
    if len(set(substitutions)) != len(substitutions) or not set(substitutions) <= set(added['routes']):
        raise ValueError('invalid holiday weekend substitution routes')
    with TemporaryDirectory(dir=output.parent) as scratch:
        draft = Path(scratch) / 'candidate'
        draft.mkdir()
        for name, digest in manifest['files'].items():
            if Path(name).name != name or (base / name).is_symlink() or sha256(base / name) != digest:
                raise ValueError(f'Original source file digest differs: {name}')
            shutil.copyfile(base / name, draft / name)
        shutil.copyfile(extension, draft / 'extended-hours.json')
        manifest['extended_verification_code_sha'] = code_sha
        manifest['extended_holiday_dates'] = read_holidays(calendar)
        if substitutions:
            manifest['holiday_weekend_substitution_routes'] = sorted(substitutions)
        manifest['files']['extended-hours.json'] = sha256(draft / 'extended-hours.json')
        (draft / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        draft.rename(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('base', 'extension', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--code-sha', required=True)
    parser.add_argument('--holiday-calendar', type=Path, required=True)
    parser.add_argument('--holiday-weekend-route', action='append', default=[])
    args = parser.parse_args()
    compose(args.base, args.extension, args.output, args.code_sha, args.holiday_calendar, args.holiday_weekend_route)
