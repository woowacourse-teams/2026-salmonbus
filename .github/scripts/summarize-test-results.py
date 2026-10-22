#!/usr/bin/env python3
"""JUnit XML 결과를 테스트 태스크별로 묶어 GitHub 단계 요약(markdown)으로 낸다.

    python3 .github/scripts/summarize-test-results.py --baseline backend/test-baseline.properties \\
        --output "$GITHUB_STEP_SUMMARY" backend
    python3 .github/scripts/summarize-test-results.py --baseline backend/test-baseline.properties --check \\
        --require tools.test,worker-app.sharedDevDataTest --output "$GITHUB_STEP_SUMMARY" dev/server/tools backend/worker-app
    python3 .github/scripts/summarize-test-results.py --list-names backend > names.txt

인자로 받은 디렉터리 아래 **/build/test-results/<태스크>/TEST-*.xml 을 읽는다.
키는 <모듈 디렉터리 이름>.<태스크>이고 test-baseline.properties 의 키와 같다.

실행 수 외의 지표는 테스트 로그(system-out) 문구에 기댄다.
로그 설정이 바뀌면 0으로 보이므로, 기준 파일에 상한이 있는 지표가 0이면 경고한다.

--check 는 기준값이 있는 실행 수가 다르거나 기준 파일에 없는 태스크가 있으면 1로 끝난다.
--require 로 넘긴 태스크는 결과 XML 이 하나도 없으면 실패로 센다(--check 와 함께 쓴다).
--list-names 는 테스트를 옮기는 변경의 전후 대조용으로 '클래스#테스트' 이름을 정렬해 출력한다.
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

CONTEXT_STARTED = re.compile(r'Started (\S+) in [0-9.]+ seconds')
POSTGRES_STARTED = re.compile(r'Container postgres:\S+ started in')
MONGO_STARTED = re.compile(r'Container mongo:\S+ started in')
FLYWAY_APPLIED = re.compile(
    r'Successfully applied (\d+) migrations? to schema .*?\(execution time (\d+):(\d+\.\d+)s\)')
TESTCONTAINERS_VERSION = re.compile(r'Testcontainers version: (\S+)')


def load_baseline(path):
    values = {}
    if not path:
        return values
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
    return values


def find_reports(roots):
    for root in roots:
        for report in sorted(Path(root).glob('**/build/test-results/*/TEST-*.xml')):
            module_dir, _, rest = report.as_posix().rpartition('/build/test-results/')
            task = rest.split('/', 1)[0]
            yield f'{Path(module_dir).name}.{task}', report


def log_text(suite):
    return ''.join(element.text or '' for element in suite.iter() if element.tag in ('system-out', 'system-err'))


def new_stats():
    return {
        'classes': 0, 'tests': 0, 'skipped': 0, 'failed': 0, 'seconds': 0.0,
        'contexts': 0, 'postgres': 0, 'mongo': 0, 'flyway': [],
    }


def collect(roots):
    stats = defaultdict(new_stats)
    suites = []
    versions = set()
    for key, report in find_reports(roots):
        suite = ET.parse(report).getroot()
        entry = stats[key]
        tests = int(suite.get('tests', 0))
        seconds = float(suite.get('time', 0))
        entry['classes'] += 1
        entry['tests'] += tests
        entry['skipped'] += int(suite.get('skipped', 0))
        entry['failed'] += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        entry['seconds'] += seconds
        text = log_text(suite)
        entry['contexts'] += sum(1 for match in CONTEXT_STARTED.finditer(text)
                                 if match.group(1) != 'GradleWorkerMain')
        entry['postgres'] += len(POSTGRES_STARTED.findall(text))
        entry['mongo'] += len(MONGO_STARTED.findall(text))
        for match in FLYWAY_APPLIED.finditer(text):
            entry['flyway'].append((int(match.group(1)), int(match.group(2)) * 60 + float(match.group(3))))
        versions.update(TESTCONTAINERS_VERSION.findall(text))
        suites.append((seconds, key, suite.get('name'), tests))
    return stats, suites, versions


def list_names(roots):
    # 파라미터 테스트는 같은 이름이 여러 번 나올 수 있어 중복을 지우지 않는다.
    names = []
    for _, report in find_reports(roots):
        for case in ET.parse(report).getroot().iter('testcase'):
            names.append(f"{case.get('classname')}#{case.get('name')}")
    print('\n'.join(sorted(names)))


def compare_executed(key, executed, baseline, check, failures, warnings):
    expected = baseline.get(f'executed.{key}')
    if expected is None:
        message = f'{key}: 기준 파일에 executed.{key} 가 없다(이번 실행 수 {executed}).'
        (failures if check else warnings).append(message)
        return '기준 없음'
    if expected == '':
        warnings.append(f'{key}: 실행 기준이 비어 있다. 이번 실행 수({executed})를 확인해 기준 파일에 적는다.')
        return '기준 미정'
    if int(expected) == executed:
        return f'{expected} 일치'
    message = f'{key}: 실행 테스트 수가 기준({expected})과 다르다(이번 {executed}).'
    (failures if check else warnings).append(message)
    return f'{expected} 다름'


def check_budgets(key, entry, executed, baseline, warnings):
    for metric, label in (('contexts', '컨텍스트 기동'), ('postgres', 'PG 기동')):
        limit = baseline.get(f'{metric}.max.{key}')
        if not limit:
            continue
        if entry[metric] > int(limit):
            warnings.append(f'{key}: {label} 횟수가 상한({limit})을 넘었다(이번 {entry[metric]}).')
        elif entry[metric] == 0 and int(limit) > 0 and executed > 0:
            warnings.append(f'{key}: {label} 수가 0이다. 로그 형식이 바뀌어 세지 못했을 수 있다.')


def summarize(stats, suites, versions, baseline, check, required, out):
    def emit(line=''):
        print(line, file=out)

    failures = []
    warnings = []
    for key in required:
        if key not in stats:
            message = f'{key}: 결과 XML 을 찾지 못했다. 테스트 태스크가 돌지 않았거나 결과 경로가 바뀌었다.'
            (failures if check else warnings).append(message)
    # 마이그레이션 검증 테스트의 부분 적용을 빼려고, 가장 큰 적용 개수(전체 적용)인 줄만 센다.
    full_migrations = max((count for entry in stats.values() for count, _ in entry['flyway']), default=0)

    emit('### 테스트 수와 무거운 자원')
    emit('실행 = tests - skipped. 컨텍스트·PG·Mongo·Flyway 는 테스트 로그 문구로 센 횟수다.')
    emit()
    emit('| 태스크 | 클래스 | 실행 | 건너뜀 | 실패 | 실행 기준 | 컨텍스트 | PG | Mongo '
         f'| Flyway {full_migrations}개 적용 | Flyway 합(초) | 클래스 시간 합(초) |')
    emit('|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|')
    for key in sorted(stats, key=lambda name: -stats[name]['seconds']):
        entry = stats[key]
        executed = entry['tests'] - entry['skipped']
        verdict = compare_executed(key, executed, baseline, check, failures, warnings)
        check_budgets(key, entry, executed, baseline, warnings)
        applied = [seconds for count, seconds in entry['flyway'] if count == full_migrations]
        emit(f"| {key} | {entry['classes']} | {executed} | {entry['skipped']} | {entry['failed']} | {verdict} "
             f"| {entry['contexts']} | {entry['postgres']} | {entry['mongo']} "
             f"| {len(applied)} | {sum(applied):.2f} | {entry['seconds']:.1f} |")

    if sum(entry['postgres'] for entry in stats.values()) > 0 and full_migrations == 0:
        warnings.append('PG 기동은 있는데 Flyway 적용 줄을 찾지 못했다. 로그 형식이 바뀌었을 수 있다.')
    if len(versions) > 1:
        warnings.append('Testcontainers 버전이 여러 개다: ' + ', '.join(sorted(versions)))

    emit()
    emit('Testcontainers 버전: ' + (', '.join(sorted(versions)) if versions else '로그에서 찾지 못했다'))
    if failures or warnings:
        emit()
        emit('#### 확인할 것')
        for message in failures:
            emit(f'- 실패: {message}')
        for message in warnings:
            emit(f'- 경고: {message}')

    emit()
    emit('### 느린 클래스')
    emit('Spring 컨텍스트와 컨테이너 시작 시간은 그것을 처음 쓴 클래스에 더해진다.')
    emit()
    emit('| 클래스 | 태스크 | 테스트 수 | 시간(초) |')
    emit('|---|---|---:|---:|')
    for seconds, key, name, tests in sorted(suites, reverse=True)[:20]:
        emit(f'| {name} | {key} | {tests} | {seconds:.1f} |')

    for message in failures:
        print(f'::error::{message}')
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('roots', nargs='+', help='build/test-results 를 찾을 디렉터리')
    parser.add_argument('--baseline', help='test-baseline.properties 경로')
    parser.add_argument('--output', help='요약을 덧붙일 파일. 없으면 표준 출력')
    parser.add_argument('--check', action='store_true', help='실행 수가 기준과 다르면 1로 끝낸다')
    parser.add_argument('--require', default='',
                        help='결과가 반드시 있어야 하는 태스크 키(쉼표 구분, 예: tools.test,worker-app.sharedDevDataTest)')
    parser.add_argument('--list-names', action='store_true', help="'클래스#테스트' 이름 목록만 출력한다")
    args = parser.parse_args()

    if args.list_names:
        list_names(args.roots)
        return 0
    required = [key.strip() for key in args.require.split(',') if key.strip()]
    stats, suites, versions = collect(args.roots)
    out = open(args.output, 'a', encoding='utf-8') if args.output else sys.stdout
    try:
        if not stats:
            print('테스트 결과 XML 을 찾지 못했다.', file=out)
            return 1 if args.check else 0
        return summarize(stats, suites, versions, load_baseline(args.baseline), args.check, required, out)
    finally:
        if out is not sys.stdout:
            out.close()


if __name__ == '__main__':
    sys.exit(main())
