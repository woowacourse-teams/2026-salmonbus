# AI에게 SSH 노선 운영을 요청하는 방법

팀원이 각자 사용하는 AI 코딩 도구에서 이 문서를 읽힌 뒤 노선 운영을 요청한다. 이 문서는 실행 절차이며 SSH 권한이나 운영 권한을 부여하지 않는다. 개인별 SSH 계정·키와 서버의 maintenance-app 실행 권한이 필요하다. 비밀키·DB 비밀번호를 문서나 채팅에 붙이지 않는다.

현재 기능의 운영 배포 여부는 확인하지 않았다. 최초 호환 버전 배포·V47 적용·API/worker의 `ROUTE_OPERATIONS_ENABLED=true` 설정이 선행되어야 한다. 운영 명령 실행 중 배포나 설정 변경을 대신 수행하지 않는다. 명령과 승인 파일 규격의 원본은 [노선 운영 안내서](route-operations-runbook.md)다.

## 팀원이 요청하는 예시

```text
backend/history/ai-route-operations.md를 읽고,
운영 SSH에 접속해서 4000번과 5600번 서비스를 꺼줘. 수집은 유지해.
```

```text
backend/history/ai-route-operations.md를 읽고,
운영 SSH에 접속해서 4000번과 5600번 수집을 꺼줘.
```

```text
backend/history/ai-route-operations.md를 읽고,
운영 SSH에 접속해서 5600번 서비스를 켜줘. 현재 수집 상태부터 확인해.
```

문서를 아직 공유 브랜치에 반영하지 않았다면 팀원에게 이 파일과 연결된 `route-operations-runbook.md`를 함께 전달한다. 이 파일을 만들었다고 다른 사람의 도구에 자동 등록되는 것은 아니다.

## 최초 준비: 팀별 경로, 개인별 접속 정보

다음 값을 팀의 실제 배포 환경에서 한 번 확인한다. 저장소에는 SSH 대상과 maintenance JAR 배포 경로가 확정되어 있지 않으므로 임의로 채우지 않는다.

| 값 | 준비할 내용 |
|---|---|
| SSH 대상 | 각 팀원의 SSH config 별칭 또는 계정@호스트. SSH 키는 각자 보관 |
| 실행 권한 | 서버에서 Java와 maintenance JAR을 실행하고 root 소유 DB 환경 파일을 읽을 수 있는 기존 sudo 권한 |
| JAR | 실제 배포한 maintenance-app JAR의 절대 경로 |
| config | 서버의 노선 운영 properties 파일 경로 |
| approval | 대상 DB에 맞는 유효한 운영 승인 JSON 파일 경로 |
| actor | 요청한 사람을 식별할 팀 계정명. AI라는 이름으로 대체하지 않음 |

config의 내용은 다음과 같다. DB 환경 파일은 기존 root 소유·비공개 파일을 사용한다.

```properties
target.kind=ACADEMY
database.env-file=/etc/salmonbus/worker.env
```

운영 승인 action은 `ACADEMY_ROUTE_OPERATIONS`다. 조회 결과의 `databaseIdentity`와 일치하고 만료되지 않은 승인 파일을 사용한다. 없으면 원본 안내서의 승인 파일 형식에 따라 팀의 승인 절차로 준비한다. `LOCAL`로 바꾸거나 검증 코드를 우회하지 않는다.

이 값은 각자 AI에 알려주거나 팀이 정한 로컬 설정 문서에 기록한다. 값이 없으면 AI는 누락된 접속 대상·경로만 질문한다. 키나 비밀번호를 요구하지 않는다.

## AI가 해석할 작업 범위

명시된 노선과 기능만 변경한다. 문서를 읽으라는 요청만으로 운영 값을 바꾸지 않는다. “SSH 접속해서 … 꺼줘/켜줘”라는 실행 요청이 있을 때 진행한다.

| 사용자 요청 | 실행할 명령 | 나머지 값 |
|---|---|---|
| 서비스/예보 꺼 | `routes-service --enabled false` | 수집 유지 |
| 서비스/예보 켜 | `routes-service --enabled true` | 수집 유지. 수집 OFF면 켤 수 없다고 알리고 수집 ON 의도를 확인 |
| 수집 꺼 | 서비스 ON이면 `routes-disable-both`, 서비스 OFF면 `routes-collection --enabled false` | 서비스 ON·수집 OFF 조합은 불가능. 서비스도 중단됨을 실행 전 설명 |
| 수집 켜 | `routes-collection --enabled true` | 서비스 유지 |
| 둘 다 꺼 | `routes-disable-both` | 수집·서비스를 원자적으로 OFF |
| 노선 꺼 / 운영 중지 | 변경 전 수집인지 서비스인지 질문 | 추측하지 않음 |

노선 번호와 GBIS ID의 확인된 매핑:

| 번호 | route-id |
|---|---|
| 5600 | `228000184` |
| 4000 | `200000314` |
| 3330 | `204000057` |

다른 노선은 저장소의 카탈로그나 공개 노선 조회에서 번호와 9자리 GBIS ID를 대조한다. `?번`, 중복된 번호, 확인되지 않는 노선은 질문하고 쓰기 전에 전체 대상 목록을 확정한다. route_version_id를 대신 쓰지 않는다.

## AI 실행 순서

1. 위 입력과 사용자의 실행 요청을 확인한다. 명시적으로 승인된 동일 작업의 허락을 반복해서 묻지 않는다. 도구 자체 승인이나 실제 권한이 필요하면 그 사유만 설명한다.
2. 기존 SSH 설정으로 접속한다. 연결 실패나 host key 변경 시 접근 설정을 자동 변경하지 않는다. 이 작업에서 키 생성·계정 추가·패키지 설치·빌드·배포·서비스 재시작은 하지 않는다.
3. 지정한 JAR/config/approval의 존재와 접근 권한을 확인한다. 파일의 비밀 내용을 출력하지 않는다. 양쪽 feature flag 적용과 호환 배포가 확인되지 않으면 조회까지만 하고, 변경이 실제 서비스에 반영된다고 보고하지 않는다.
4. 대상 노선 모두 `routes-status`로 조회한다. `target=ACADEMY`, 예상한 DB 식별값, READY, 현재 collection/service 값, revision을 확인한다. 서비스 ON 또는 수집 ON에는 workerFresh와 전역 활성화 상태도 확인한다.
5. 조회 결과로 대상·명령·유지되는 값을 짧게 알리고, 노선별로 하나씩 실행한다. 수동 중단은 `MANUAL`, 안내문은 요청 의도에 맞게 쓴다. 파업이 확인됐고 공식 URL이 있을 때만 `STRIKE`/`PARTIAL`과 `--source-url`을 사용한다.
6. 각 변경에 현재 revision, 새로운 request UUID, 실제 actor를 넣는다. SSH 응답이 유실되어 재시도할 때는 UUID와 전체 인자를 그대로 유지한다. 명령이 성공했는지 불명확하면 새 UUID로 중복 요청하지 말고 먼저 상태를 조회한다.
7. 매번 다시 조회해 요청값과 적용값을 구분한다. 서비스 OFF는 serviceRequested=false·serviceEnabled=false와 수집 유지 여부를 확인한다. 서비스 ON 요청 성공만으로 복구 완료라고 하지 않는다. WARMING_UP에서 새 관측·발행 후 실제 ON이 되는지 제한된 시간 동안 확인하고, 계속 준비 중이면 마지막 상태와 미완료 이유를 보고한다.
8. 여러 노선 중 실패가 나면 후속 변경을 멈추고 성공·실패·미실행 노선을 구분한다. 노선 간 일괄 트랜잭션은 없다. 이미 성공한 노선을 임의로 되돌리지 않는다. `REVISION_CONFLICT`는 다른 변경 가능성이 있으므로 revision을 갈아 끼워 강행하지 않는다.
9. 결과에 노선 번호/ID, 변경 전후 수집·요청 서비스·실제 서비스, revision/appliedRevision, request-id를 남긴다. DB 자격과 승인 파일 내용은 출력하지 않는다. 요청한 변경 외의 재시작이나 다른 노선 변경은 하지 않는다.

수집 OFF 직전에 시작한 요청은 끝날 수 있다. 서비스 OFF 후 이미 브라우저에 전달된 응답까지 즉시 회수되는 것은 아니다. 요청값 저장, worker 적용, API/화면 반영을 같은 완료 상태로 표현하지 않는다.

## 서버에서 사용하는 명령 형식

아래는 서버 안에서 실행하는 템플릿이다. 각 `<값>`을 실제 값으로 치환하고 기존 권한에 맞춰 `sudo` 사용 여부를 정한다. 사용자 문장을 쉘 코드로 삽입하지 말고 인자를 안전하게 인용한다.

```sh
java -jar '<JAR>' routes-status \
  --config '<CONFIG>' --route-id 200000314

java -jar '<JAR>' routes-service \
  --config '<CONFIG>' --approval '<APPROVAL>' \
  --route-id 200000314 --expected-revision '<현재 revision>' \
  --actor '<요청자 팀 계정>' --request-id '<새 UUID>' \
  --enabled false --reason MANUAL --message '예보 제공을 일시 중단합니다.'
```

수집만 OFF 가능한 상태라면 명령을 `routes-collection --enabled false`로 바꾼다. 서비스가 ON이면 `routes-disable-both`를 사용하고 **`--enabled`는 빼야 한다**. 나머지 변경 인자는 동일하다. 예상하지 못한 오류는 원본 안내서와 실제 종료 코드를 확인하고 보고한다.
