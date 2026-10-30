#!/usr/bin/python3
"""Server-local route controls. Uses maintenance-app, never writes SQL directly."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import uuid

CONFIG = Path('/etc/salmonbus/route-control.json')


def private_root_file(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError('설정은 root 소유 일반 파일, 권한 0600이어야 합니다: ' + str(path))
    return path


def parse(argv=None):
    parser = argparse.ArgumentParser(
        description='노선 수집/서비스 운영 (서버 내부 전용)',
        epilog='서버 사용 안내: /usr/local/share/salmonbus/route-control.md. 상태 조회: sudo route-control 4000 status')
    parser.add_argument('route', help='노선 번호 또는 9자리 GBIS ID')
    parser.add_argument('control', choices=['status', 'service', 'collection', 'both'])
    parser.add_argument('state', nargs='?', choices=['on', 'off'])
    parser.add_argument('--reason', default='MANUAL', choices=['MANUAL', 'STRIKE', 'PARTIAL', 'RECOVERING'])
    parser.add_argument('--message', default='운영자가 노선 상태를 변경했습니다.')
    parser.add_argument('--source-url', default='')
    args = parser.parse_args(argv)
    if (args.control == 'status') != (args.state is None):
        parser.error('status에는 on/off를 쓰지 않고, 변경에는 on/off를 지정하세요.')
    if args.control == 'both' and args.state != 'off':
        parser.error('both는 off만 지원합니다. 수집과 서비스를 각각 켜세요.')
    if args.reason in ['STRIKE', 'PARTIAL'] and not args.source_url:
        parser.error('파업/일부 운행 사유에는 --source-url이 필요합니다.')
    return args


def invoke(config, arguments):
    # JVM/DB 설정은 고정된 JAR/config에서 읽는다. 호출자 환경으로 덮어쓰지 않는다.
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'}
    command = [config['java'], '-Xmx128m', '-XX:ActiveProcessorCount=1', '-jar', config['jar']]
    result = subprocess.run(command + arguments, env=env, capture_output=True, text=True, timeout=60)
    if result.returncode:
        try:
            code = json.loads(result.stderr)['code']
        except (ValueError, KeyError, TypeError):
            code = 'MAINTENANCE_COMMAND_FAILED'
        raise RuntimeError(str(code) + ' (자동 재시도하지 않았습니다)')
    reply = json.loads(result.stdout)
    if reply['status'] != 'succeeded':
        raise RuntimeError('MAINTENANCE_RESPONSE_INVALID')
    return reply['result']


def read_status(config, route):
    result = invoke(config, ['routes-status', '--config', config['maintenanceConfig'], '--route-id', route])
    if result['target'] != 'ACADEMY' or result['databaseIdentity'] != config['databaseIdentity']:
        raise ValueError('대상 DB 불일치. 변경하지 않습니다.')
    operation = result['preview']['operation']
    if operation is None:
        raise ValueError('운영 등록되지 않은 노선입니다.')
    return result


def change_arguments(args, route, operation, actor, request):
    if args.control == 'collection' and args.state == 'off' and operation['serviceRequested']:
        raise ValueError('서비스가 켜져 있습니다. 수집과 서비스 모두 중단하려면 both off를 쓰세요.')
    if args.control == 'service' and args.state == 'on' and not operation['collectionEnabled']:
        raise ValueError('수집이 OFF입니다. collection on 후 service on을 실행하세요.')
    command = 'routes-disable-both' if args.control == 'both' else 'routes-' + args.control
    result = [command, '--route-id', route, '--expected-revision', str(operation['revision']),
              '--actor', actor, '--request-id', request, '--reason', args.reason,
              '--message', args.message, '--source-url', args.source_url]
    if args.control != 'both':
        result += ['--enabled', str(args.state == 'on').lower()]
    return result


def save_record(path, record):
    # 요청 UUID별 기록을 0600으로 저장한다. 실패해도 원래 인자를 보존한다.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(record, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def execute(args, config, actor):
    route = config['routes'].get(args.route, args.route)
    if not re.fullmatch(r'[0-9]{9}', route):
        raise ValueError('등록되지 않은 노선 번호입니다. 설정 대응표나 9자리 GBIS ID를 확인하세요.')
    before = read_status(config, route)
    if args.control == 'status':
        print(json.dumps(before, ensure_ascii=False, indent=2))
        return
    request = str(uuid.uuid4())
    arguments = change_arguments(args, route, before['preview']['operation'], actor, request)
    directory = Path('/var/lib/salmonbus/route-control')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('기록 디렉터리는 root 소유, 권한 0700이어야 합니다.')
    path = directory / (request + '.json')
    record = {'requestId': request, 'actor': actor, 'arguments': arguments,
              'before': before, 'outcome': 'submitted'}
    save_record(path, record)
    print('request-id=' + request + ' record=' + str(path), flush=True)
    try:
        with tempfile.TemporaryDirectory(prefix='salmonbus-route-') as temporary:
            receipt = Path(temporary) / 'approval.json'
            approval = {'action': 'ACADEMY_ROUTE_OPERATIONS', 'artifactSha256': None,
                        'databaseIdentitySha256': config['databaseIdentity'],
                        'expiresAt': (datetime.now(timezone.utc) + timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:%SZ'),
                        'schemaVersion': 'salmonbus-migration-approval-v1'}
            receipt.write_text(json.dumps(approval, sort_keys=True, separators=(',', ':')) + '\n')
            receipt.chmod(0o600)
            invoke(config, arguments + ['--config', config['maintenanceConfig'], '--approval', str(receipt)])
        record['outcome'] = 'change_accepted'
        record['after'] = read_status(config, route)
        save_record(path, record)
        print(json.dumps(record['after'], ensure_ascii=False, indent=2))
        print('요청 저장 완료. 서비스 ON의 WARMING_UP은 공개 완료가 아닙니다.')
    except Exception:
        record['outcome'] = 'needs_status_check'
        save_record(path, record)
        print('자동 재시도하지 않습니다. status와 기록을 확인하세요. 요청이 이미 반영됐을 수 있습니다.', file=sys.stderr)
        raise


def main():
    args = parse()
    if os.geteuid() != 0:
        raise ValueError('sudo route-control로 실행하세요.')
    config = json.loads(private_root_file(CONFIG).read_text())
    if not config.get('ready', False):
        raise ValueError('아직 운영 기능이 배포/활성화되지 않았습니다. /usr/local/share/salmonbus/route-control.md 확인')
    if not re.fullmatch('[0-9a-f]{64}', config['databaseIdentity']):
        raise ValueError('databaseIdentity에 조회한 운영 DB 식별값을 고정하세요.')
    for key in ['java', 'jar', 'maintenanceConfig']:
        if not Path(config[key]).is_absolute():
            raise ValueError('설정 경로는 절대 경로여야 합니다.')
    private_root_file(config['maintenanceConfig'])
    actor = os.environ.get('SUDO_USER') or 'root'
    execute(args, config, actor)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.SubprocessError) as error:
        print('route-control: ' + str(error), file=sys.stderr)
        sys.exit(1)
