# maintenance-app 노선 운영 명령

어드민 구현 전 서버의 간편 명령은 [route-control 설치·사용](route-control-script.md)을 따른다.

팀원이 AI로 SSH 운영을 요청하는 절차는 [AI SSH 운영 안내](ai-route-operations.md)를 따른다.

## 적용 상태

이 문서는 현재 구현한 명령과 최초 전환 절차를 설명한다. **부분 컴파일·실제 명령·분리된 로컬 런타임 검증은 수행했지만, 전체 CI와 실제 SSH 운영 적용은 확인하지 않았다.** 운영 명령은 호환 버전의 최초 배포와 설정 적용을 확인한 뒤 실행한다. 관리자 웹·운영 쓰기 HTTP API는 만들지 않는다.

최초 코드 배포와 V47과 V48 적용, API/worker 양쪽의 `ROUTE_OPERATIONS_ENABLED=true` 설정 반영에는 프로세스 교체가 필요하다. 그 뒤의 등록·개별 ON/OFF는 DB 요청을 실행 중인 worker/API가 읽으므로 서비스 프로세스를 재시작하지 않는다. maintenance-app 명령 자체는 일회성 Java 프로세스다.

전역 `COLLECTION_ENABLED`, `FORECAST_ENABLED`는 기존 기동 설정이다. 해당 기능이 꺼져 있으면 개별 ON 요청으로 스케줄·모델 로더를 생성하지 않는다. `routes-status`의 workerFresh·globalCollectionEnabled·globalForecastEnabled를 먼저 본다.

## 명령과 상태

| 명령 | 동작 |
|---|---|
| `routes-list` | 운영 등록 목록. 요청값과 실제 공개값을 함께 반환 |
| `routes-preview`, `routes-status` | DB 식별값, 요청/적용 revision, 등록/지도 상태, worker 응답·한도, 최근 관측·발행 확인. 데이터 변경 없음 |
| `routes-register` | 9자리 GBIS ID의 등록 요청. 기본 수집 ON / 서비스 ON 요청. 등록 완료와 새 예보 발행 전에는 비공개 |
| `routes-collection --enabled true/false` | 수집 대상 제어 |
| `routes-service --enabled true/false` | 공개·예보 생성 제어. ON은 준비 단계부터 시작 |
| `routes-disable-both` | 수집·서비스를 한 트랜잭션으로 OFF |
| `routes-notice` | 스위치를 유지하고 안내 변경. 예고는 PLANNED 사유 사용 |
| `routes-retry` | FAILED 노선의 카탈로그 등록 재요청 |
| `routes-retry-geometry` | 지도 형상 조회만 다시 요청. 수집·서비스 값 유지 |

READ 명령 이외에는 `--request-id UUID --actor 운영자`가 필요하다. 등록 이외 변경에는 `--expected-revision 현재값 --reason 사유 --message 안내문`도 필요하다. `--source-url`은 선택이지만 STRIKE/PARTIAL에는 공식 출처 URL이 필수다. 허용 사유는 NORMAL, MANUAL, ONBOARDING, PLANNED, STRIKE, PARTIAL, MAINTENANCE, RECOVERING이다. 모르는 옵션·잘못된 boolean·9자리 아닌 노선 ID는 거절한다.

`request-id`는 같은 요청을 재시도할 때 그대로 유지한다. expected revision·운영자·action·ON/OFF·사유·본문·출처가 달라지면 새 UUID를 사용한다. 같은 키에 다른 내용을 넣으면 REQUEST_ID_REUSED, 다른 운영자가 이미 변경했으면 REVISION_CONFLICT다. 재시도 결과는 최초 결과의 옛 스냅샷이 아니라 현재 상태를 반환한다.

수집 OFF / 서비스 ON 조합은 허용하지 않는다. 서비스 ON 중 수집만 끄면 COLLECTION_REQUIRED가 발생한다. 서비스부터 끄거나 routes-disable-both를 사용한다. 서비스 OFF여도 기존 공개 노선·정류장·즐겨찾기는 유지한다. 아직 한 번도 공개하지 않은 신규 노선은 일반 검색에 자동 노출하지 않는다.

## SSH 실행 준비

기존 서버 접근 권한과 maintenance-app 배포 산출물을 사용한다. 별도 도메인·관리자 로그인·GBIS 키 복사는 필요 없다. JAR 경로는 배포한 실제 산출물을 확인해 지정한다. 새 JAR를 현장에서 소스 빌드하는 절차는 포함하지 않는다.

설정 파일 예시:

```properties
target.kind=ACADEMY
database.env-file=/etc/salmonbus/worker.env
```

DB 환경 파일은 기존 보안 조건대로 root 소유·비공개 일반 파일이어야 한다. LOCAL 대상은 loopback DB만 허용한다. 비밀번호를 명령줄 인자나 출력으로 전달하지 않는다.

기존 승인 파일 형식을 유지하며 운영 쓰기 action은 `ACADEMY_ROUTE_OPERATIONS`다. preview가 반환한 databaseIdentity와 미래 UTC 만료 시각을 넣는다. 다음은 값을 채우기 위한 템플릿이며 그대로 실행 가능한 승인 파일이 아니다.

```json
{"action":"ACADEMY_ROUTE_OPERATIONS","artifactSha256":null,"databaseIdentitySha256":"<preview의 databaseIdentity>","expiresAt":"<미래 UTC ISO 시각>","schemaVersion":"salmonbus-migration-approval-v1"}
```

승인 JSON은 기존 CanonicalJson 규칙에 맞는 canonical 한 줄 JSON이어야 하며 파일 권한은 0600 또는 0400이다. actor 인자는 감사 기록용이지 인증 대체 수단이 아니다. 실제 쓰기 권한은 서버 접근·DB 자격·승인 파일 검증으로 제한한다.

## 사용 순서 예시

아래 경로와 UUID·revision은 예시다. **실제 대상 DB와 현재 revision을 먼저 확인한 뒤** 바꿔 사용한다. source URL도 확인한 공식 공지로 바꾼다.

```sh
java -jar /path/to/maintenance-app.jar routes-status \
  --config /path/to/routes.properties --route-id 228000184

java -jar /path/to/maintenance-app.jar routes-register \
  --config /path/to/routes.properties --approval /path/to/approval.json \
  --route-id 228000184 --actor operator \
  --request-id 6bad609f-f2b5-4bb7-9fea-b9bb4e68aa10
```

등록은 살아 있는 worker의 수집 한도와 전역 예보 활성화 조건을 확인한다. 기존 등록 노선의 재등록은 ON/OFF 값을 덮어쓰지 않는다. 등록 뒤 READY와 지도 상태를 확인한다. FAILED면 lastError를 읽고 원인을 해결한 뒤 routes-retry한다. 지도만 FAILED면 routes-retry-geometry한다. 등록·지도 작업은 기존 수집 실행기에서 회차당 한 노선씩 처리하므로 10초 이내 완료를 보장하지 않는다.

```sh
java -jar /path/to/maintenance-app.jar routes-collection \
  --config /path/to/routes.properties --approval /path/to/approval.json \
  --route-id 228000184 --expected-revision 1 --actor operator \
  --request-id 88fcbb6c-1f9a-42dd-8082-5d58d690bd11 \
  --enabled true --reason NORMAL --message '수집 시작'
```

수집 ON은 worker가 보고한 호출 예산으로 검사한다. 운영자가 무제한 한도를 넘겨 우회하는 인자는 없다. workerFresh=false면 먼저 worker 상태를 확인한다. 예산은 기존 시간대별 주기와 키 한도에 10% 여유를 둔 추정이며 실제 호출 예약 한도가 최종 차단한다. 노선 추가로 회차가 길어질 수 있으므로 최근 관측 지연도 확인한다.

서비스 ON 예시는 현재 revision이 2인 경우다.

```sh
java -jar /path/to/maintenance-app.jar routes-service \
  --config /path/to/routes.properties --approval /path/to/approval.json \
  --route-id 228000184 --expected-revision 2 --actor operator \
  --request-id e2ed6178-dcd2-466f-a48b-82194e731b82 \
  --enabled true --reason NORMAL --message '새 예보 확인 중'
```

WARMING_UP은 공개 완료가 아니다. 활성 모델의 해당 노선·참조 지원과 요청 이후 새 관측·유효한 예보 발행이 있어야 ON으로 바뀐다. MODEL_NOT_SUPPORTED면 모델 번들 학습/지원 작업이 별도로 필요하다. 이 명령이 모델을 자동으로 학습·승격하지 않는다.

## 5600·4000 파업 대응

- 5600 = 228000184, 4000 = 200000314. 노선 번호와 내부 route_version_id를 인자로 쓰지 않는다.
- 예고만 있을 때: routes-notice, reason=PLANNED. collection/service 값을 유지한다.
- 실제 파업 확인: routes-service, enabled=false, reason=STRIKE. 일부 운행은 PARTIAL. 공식 source-url과 안내문을 기록한다. 수집은 ON 유지한다.
- 재개 확인: routes-service, enabled=true, reason=RECOVERING. 사건 기간을 종료하고 WARMING_UP에서 새 관측·예보를 기다린다.
- 심야 관측은 최대 10분, 그 밖은 5분의 기존 공개 신선도 기준을 만족해야 실제 공개된다. 과거 파업 기간의 미처리 배치를 재개 후 뒤늦게 예보하지 않는다.

운영 상태는 API 요청에서 확인한다. 이번 PR에는 프런트엔드 변경을 포함하지 않는다. 이미 화면에 표시된 예보를 운영 상태에 맞춰 숨기는 처리와 중단 안내는 별도 작업이 필요하다. 서버의 서비스 OFF만으로 브라우저가 이미 받은 응답을 회수할 수는 없다.

수집 OFF 전 이미 시작한 외부 요청은 완료·보존될 수 있다. 현재 단일 worker 수집 실행 모델을 유지한다. 다중 worker의 중복 외부 수집까지 이번 기능이 해결하지는 않는다.

## 최초 적용과 되돌리기

1. 테스트·실제 프로세스 검증 후 V47의 기존 20개 노선 backfill을 운영 공개/수집 목록과 대조한다. 지도 카탈로그 전체를 등록하지 않는다.
2. API/worker 호환 버전을 배포하고 양쪽 feature flag를 켠다. DB가 개별 스위치의 유일한 원본이 되며 정적 route-ids와 합집합으로 읽지 않는다.
3. 조회·등록·수집 ON·서비스 준비·OFF·복구가 같은 API/worker PID에서 반영되는지 확인한다. 로컬에서 개별 동작을 나눠 확인했으며, 배포 환경의 연속 시나리오는 별도 확인 대상이다.
4. 상태 활성화 이후 구버전으로 raw 롤백하면 OFF를 무시할 수 있다. 긴급 공개 보류·호환 버전 복구를 먼저 결정하고 사건·감사 이력은 삭제하지 않는다.

파업 기간은 원본을 보존하고 관측 품질 view·source/arrival 기간 검사로 파생 입력에서 제외한다. 서비스 OFF 전체를 학습 부적격으로 취급하지 않아 학습 전용 수집은 유지한다. 저장된 archive를 별도 소비하는 외부 학습 파이프라인에는 같은 사건 기간 규칙을 적용해야 하며, 이 저장소 밖 소비자 반영은 미검증이다.

운영 제어를 활성화하면 외부 maintenance-app의 품질 버전 변경을 누락하지 않도록 `forecast.quality-memory`의 프로세스 로컬 캐시를 사용하지 않고 기존 DB 조회 경로를 사용한다. 현재 기본값은 캐시 OFF다. 기존 환경에서 캐시를 켜 사용했다면 조회량·지연 변화는 배포 전 확인해야 한다. 이 변경의 운영 성능은 아직 실측하지 않았다.


## 설정 변경 알림과 연결 복구

`ROUTE_OPERATIONS_ENABLED=true`인 API와 worker는 기본적으로 PostgreSQL `LISTEN/NOTIFY`를 사용한다.
V48이 `route_operation` 변경 트리거를 만들며, 커밋된 변경의 GBIS 노선 ID를 알린다.
API와 worker는 처음 구독한 뒤 전체 상태를 읽고, 이후 알림의 노선만 읽어 메모리 상태를 교체한다.
수집 회차와 호출 직전, API의 운영 상태 확인은 메모리 조회다. maintenance-app은 계속 DB를 직접 읽고 쓴다.
예보 발행 트랜잭션의 `allowPublication`은 DB 조회와 행 잠금을 그대로 유지한다.

알림 연결은 각 프로세스에서 풀 연결 하나를 계속 사용한다. 현재 worker 풀 최대 3개 중 하나를 사용하므로
일반 작업은 나머지 2개를 공유한다. 풀 대기와 DB 최대 연결 수는 배포 환경에서 확인해야 한다.
연결을 5초마다 확인하며, 장애를 감지하면 캐시를 폐기한다. 마지막 확인 후 15초가 지난 상태도 반환하지 않는다.
재연결은 1초 간격으로 시도하고, 구독 후 전체 상태를 다시 읽어 연결 공백의 변경을 복구한다.
이 기간에는 운영 상태 조회가 실패하므로 정상 응답과 수집이 일시적으로 제한될 수 있다.
알림 수신과 캐시 갱신은 비동기이므로 명령 커밋과 모든 프로세스 반영이 동시에 완료되지는 않는다.
장애 감지 전까지의 짧은 구간도 남는다. 이미 응답한 화면의 표시 변경은 이번 PR 범위 밖이다.
발행 직전 DB 검사는 이 알림 지연과 별도로 OFF를 확인한다.

문제 조사 때만 `route-operations.notifications-enabled=false`로 기존 DB 직접 조회 경로를 사용할 수 있다.
이 프로세스 설정을 바꾸려면 재시작해야 한다. 노선별 ON/OFF에는 필요하지 않다.
반복 발행 등에서 `applied_at`만 갱신되면 알림을 생략하며, 실제 상태나 적용 revision이 바뀌면 알린다.
