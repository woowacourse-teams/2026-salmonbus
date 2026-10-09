#!/usr/bin/env python3
"""Read-only preflight on EC2. Write a candidate; NEVER reload Alloy or alter PostgreSQL.
Usage: sudo python3 prepare_postgres.py --base /etc/alloy/salmonbus-postgres-queries.yaml --output /tmp/salmonbus-queries-candidate.yaml
Requires existing monitoring DSN, libpq and PyYAML. All subprocess error text is suppressed.
"""
import argparse
import ctypes
import ctypes.util
import json
import os
from pathlib import Path
import subprocess
import yaml

ROOT = Path(__file__).resolve().parent

class _ConnectionOption(ctypes.Structure):
    _fields_ = [(name, ctypes.c_char_p) for name in
                ('keyword', 'envvar', 'compiled', 'val', 'label', 'dispchar')]
    _fields_ += [('dispsize', ctypes.c_int)]


def connection_environment(dsn):
    """Parse URI/key-value DSNs with libpq without connecting or exposing credentials."""
    library = ctypes.util.find_library('pq')
    if not library:
        raise RuntimeError('libpq is required to parse connection settings')
    libpq = ctypes.CDLL(library)
    libpq.PQconninfoParse.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_char_p)]
    libpq.PQconninfoParse.restype = ctypes.POINTER(_ConnectionOption)
    libpq.PQconninfoFree.argtypes = [ctypes.POINTER(_ConnectionOption)]
    libpq.PQconninfoFree.restype = None
    libpq.PQfreemem.argtypes = [ctypes.c_void_p]
    libpq.PQfreemem.restype = None
    error = ctypes.c_char_p()
    if '\0' in dsn:
        raise RuntimeError('Invalid connection settings; details suppressed')
    options = libpq.PQconninfoParse(dsn.encode(), ctypes.byref(error))
    try:
        if not options:
            raise RuntimeError('Invalid connection settings; details suppressed')
        env = {}
        index = 0
        while options[index].keyword:
            option = options[index]
            if option.val is not None:
                if not option.envvar:
                    raise RuntimeError('Connection option has no environment equivalent')
                env[option.envvar.decode()] = option.val.decode()
            index += 1
        return env
    finally:
        if options:
            libpq.PQconninfoFree(options)
        if error:
            libpq.PQfreemem(ctypes.cast(error, ctypes.c_void_p))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--base', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--dsn-file', type=Path, default=Path('/etc/alloy/salmonbus-postgres.dsn'))
    args = p.parse_args()
    if args.output.exists() or args.base.resolve() == args.output.resolve():
        raise SystemExit('Refusing overwrite')
    env = os.environ.copy()
    # PGDATABASE is a database name, not a container for an entire connection URI.
    # Keep parsed credentials in the child environment, never argv or stdout.
    env.update(connection_environment(args.dsn_file.read_text().strip()))
    env['PGAPPNAME'] = 'salmonbus-diagnostics-preflight'
    env['PGCONNECT_TIMEOUT'] = '5'
    env['PGOPTIONS'] = '-c default_transaction_read_only=on -c statement_timeout=2000 -c lock_timeout=100'
    def sql(query):
        r = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=query,
                           capture_output=True, text=True, env=env, timeout=10)
        if r.returncode: raise RuntimeError('Preflight SQL failed; connection/query details suppressed')
        return r.stdout.strip()
    result = yaml.safe_load(args.base.read_text())
    extra = yaml.safe_load((ROOT/'postgres-extra.yaml').read_text())
    available = sql("SELECT (current_setting('server_version_num')::int >= 140000 AND to_regclass('pg_stat_statements') IS NOT NULL)::int;") == '1'
    if available:
        try: sql('SELECT count(*) FROM pg_stat_statements;')
        except RuntimeError: available = False
    if available: extra.update(yaml.safe_load((ROOT/'postgres-statements.yaml').read_text()))
    for name, spec in extra.items():
        if name in result: raise RuntimeError('Duplicate query name: '+name)
        sql(spec['query'])  # discard result; no raw query/connection data is printed
    result.update(extra)
    with args.output.open('x') as f: yaml.safe_dump(result, f, sort_keys=False)
    print(json.dumps({'candidate_written': True, 'optional_sql_statistics': available,
                      'alloy_reloaded': False, 'database_settings_changed': False}))

if __name__ == '__main__':
    try: main()
    except Exception: raise SystemExit('Preflight failed; no reload or database changes performed')
