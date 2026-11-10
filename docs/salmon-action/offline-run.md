# 연어행동 오프라인 실행기

## 무엇을 하는가

입력 폴더에 담긴 과거 자료(도착 스냅샷, 관측 묶음, 차량 관측, 예보 발행, 좌석 예보)를 로컬 임시 PostgreSQL에 넣고, 판정 시각마다 제품의 대기 판정 코드를 그대로 돌려 집계를 쓴다. 그다음 판정 시각 뒤의 차량 관측에서 실제 결과를 읽어 판정과 맞춰 본다. SAL-392가 답하려는 질문 가운데 사유 코드 분포, 판정하지 못한 이유(Gap) 분포, 좌석 예보 확률이 비는 원인, 근사 확률과 대기 실패 위험이 실제 결과와 맞는 정도, 독립 가정이 틀리는 정도를 세는 도구다.

- 운영 DB, 운영 서버, 실서비스 API 키에 접속하지 않는다. 입력은 사람이 미리 뽑아 둔 파일이다.
- 제품 코드는 `ArrivalStopSource`, `SeatForecastProbabilitySource`, `BoardQueryService`, `ActionDecider`를 고치지 않고 쓴다. DB 읽기 두 곳(`ArrivalReadRepository`, `BoardQueryRepository`)만 "판정 시각 기준"으로 읽는 실행기 전용 구현으로 바꿔 끼운다.
- 지금은 대기 판정만 돈다. 이동 후보는 연결하지 않았다(후보 조회가 언제나 빈 목록을 돌려준다).
- 코드는 `backend/api-app/src/test/java/com/gustler/backend/api/salmon/offline`에 있다. 테스트 소스셋이라 배포되지 않는다.

`scripts/check_arrival_assumptions.py`와는 역할이 다르다. 그 스크립트는 도착 스냅샷만 보고 창 덮임 가정 하나의 위반 비율을 세고 판정은 돌리지 않는다.

## 도착 스냅샷을 먼저 모아야 한다

도착 스냅샷(arrival_snapshot)은 과거 구간이 DB에 남지 않는다.

- 지워지는 조건과 호출 견적은 아래 "보존 견적을 낸다"에 있다. 보존 기간, 정류장 수, 수집 간격은 정해지지 않았고 worker 설정에 넣지 않았다.
- 수집은 정해진 주기가 아니라 누군가 그 정류장의 도착 정보를 조회했을 때만 일어난다.

그래서 판정을 돌리려는 구간 동안, 판정할 정류장의 스냅샷이 쌓이고 지워지지 않게 하는 일이 추출보다 먼저다.

도착 스냅샷 없이도 실행기는 돈다. 그때는 모든 판정의 도착 목록이 "스냅샷 없음"이고, 볼 수 있는 것은 보드 진단(`diagnostics` 블록)뿐이다.

## 보존 견적을 낸다

보존 기간, 정류장 수, 수집 간격은 정해지지 않았다. `offline/preservation-inputs.example.json`은 그 세 칸을 null로 둔다. worker는 이 파일을 읽지 않는다. `backend/worker-app/src/main/resources/application.yml`의 `arrivals.retention`은 10m 그대로다.

행이 정리 대상이 되는 조건은 `ArrivalRetentionJob`에 있다. `stale_at`은 수신 시각에 `arrivals.validity`(60초)를 더한 값이다. 정리는 `arrivals.cleanup-interval`(60초)마다 한 번이고, 한 번에 500건까지다. 지워지는 행은 `stale_at`이 지금에서 retention을 뺀 시각보다 이전인 행이다. 같으면 남는다. 기본 retention이 10분이면, 수신 뒤 11분(60초와 10분)이 지난 다음 정리부터 대상이다. 앞에 500건 이상이 있으면 그 행은 그 회차에 지워지지 않는다. 아래 견적은 한 회차에 지울 행이 500건을 넘지 않을 때의 수다.

수집은 그 정류장을 조회할 때만 일어난다. 재수집은 `refresh-interval`(40초)보다 빨라지지 않는다. 더 짧은 간격을 넣으면 견적은 40초로 올려 센다. 도착 호출 예산은 노선 관측 예산과 따로이고, 키 하나의 기본 하루 한도는 10000이다. 속도 제한은 이 한도를 86400으로 나눈 뒤, 한 번에 `queue-capacity`(8)만큼 모아 둔다. `ArrivalRateLimit`이 그 버킷이다. 키 하나의 기본 한도에서 1시간 창의 최대 호출은 424이다. 이 수는 창이 시작될 때 버킷이 가득 차 있고, 그 창의 호출이 견적의 호출뿐일 때의 수다. 이용자 조회와 실패한 호출은 들어 있지 않다. 운영의 키 개수는 여기 없다. 키가 둘이면 하루 한도를 합으로 넣어 다시 센다.

SAL-390은 수신 간격이 120초 이하인 쌍만 비교한다. 간격이 그보다 길면 그 비교의 쌍이 없다. 간격을 120초로 정하라는 뜻은 아니다.

견적에는 기간 넷, 정류장 수, 행당 바이트가 모두 필요하다. 바이트의 기본값은 없다. 저장 payload의 크기는 재지 않았다.

```
python3 -I docs/salmon-action/scripts/preservation_cost.py estimate \
  --worker-yml backend/worker-app/src/main/resources/application.yml \
  --stations <정류장 수> \
  --interval <간격> \
  --window <수집 창> \
  --retain-for <보존 기간> \
  --extract-after <창이 끝난 뒤 추출까지> \
  --bytes <행당 가정 바이트>
```

self-test가 잠그는 예는 이렇다. 정류장 4, 간격 40초, 창 1시간, 보존 10분, 추출을 창의 끝에 하면 남는 행은 64이다. 호출은 360이고, 창이 끝날 무렵 11분 안에 받은 행만 남는다. 같은 호출을 7일 동안 두고 창이 끝난 뒤 24시간에 뽑는 입력은 견적이 받을 뿐이고, 그 7일을 운영 값으로 넣지 않는다.

`python3 -I docs/salmon-action/scripts/preservation_cost.py --self-test`는 위 예, worker 설정의 현재 값, 추출 스크립트와 `InputFolder`의 `arrival_snapshot` 열, 출퇴근 시각에 추출이 멈추는지를 확인한다. 출력은 호출 수, 행 수, 가정 바이트의 곱이다. 차량 ID와 payload는 출력하지 않는다.

retention만 늘리면 이미 조회된 정류장의 행만 더 남는다. 조회가 없는 정류장의 행은 생기지 않는다. 그 수는 이 견적에 없다.

## 입력 폴더 뽑기

`scripts/extract_offline_input.sh`가 PostgreSQL에서 아래 "입력 폴더 규격"대로 뽑는다. 읽기 전용 SELECT만 쓰고 번호판과 예보의 특징 값은 뽑지 않는다.

```
PGHOST=... PGPORT=... PGDATABASE=... PGUSER=... PGPASSWORD=... \
  docs/salmon-action/scripts/extract_offline_input.sh \
  2026-11-12T10:00:00+09:00 2026-11-12T13:00:00+09:00 /절대/경로/새입력폴더 204000057
```

- 인자는 판정 구간의 시작과 끝, 새 출력 폴더, 노선의 source_route_id(여러 개 가능)다.
- 운영 DB에서 돌릴 때는 운영 조회 승인 뒤, 출퇴근 시간(KST 07시부터 10시, 17시부터 20시) 밖에 실행한다. 스크립트가 그 시간대에는 멈춘다. 로컬 DB로 시험할 때만 `SALMON_EXTRACT_LOCAL=1`로 이 검사를 끈다.
- 관측 묶음과 차량 관측은 시작 10분 전부터 끝 2시간 뒤까지 뽑는다. 보드가 판정 시각의 10분 전 묶음까지 보고, 실제 결과는 판정 뒤 2시간까지 찾기 때문이다. 예보 발행과 좌석 예보는 시작 10분 전부터 끝까지, 도착 스냅샷은 시작부터 끝까지다.
- 한 시간씩 끊어 읽고 조회 사이에 2초 쉰다. 조회마다 출퇴근 시간인지 다시 보고 그 시간에 들어서면 멈춘다. 멈춘 폴더에는 `INCOMPLETE` 파일이 남는다. 끝까지 뽑았을 때만 이 파일을 지운다.
- 운영 추출의 첫 파일에서 확인할 것: `arrival_snapshot`의 payload 안 receivedAt 표기(문자열인지 숫자인지). 실행기는 payload를 제품과 같은 방식으로 읽으므로 그대로 읽힐 것으로 보지만 운영 자료로 확인한 적은 없다.

같은 폴더의 `arrival_snapshot.csv.gz`를 창 덮임 확인 스크립트가 읽는다(SAL-390).

```
python3 -I docs/salmon-action/scripts/check_arrival_assumptions.py --snapshot-csv /절대/경로/새입력폴더/arrival_snapshot.csv.gz
```

## 실행

Docker가 켜져 있어야 한다(Testcontainers가 `postgres:18` 컨테이너를 띄운다). `backend` 디렉터리에서 실행한다.

```
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.13.0+9-tem
export PATH=$JAVA_HOME/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH
export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock

./gradlew :api-app:runSalmonOffline \
  -PsalmonOfflineInput=/절대/경로/입력폴더 \
  -PsalmonOfflineSettings=$PWD/../docs/salmon-action/offline/settings-example.json \
  -PsalmonOfflineOutput=/절대/경로/새출력폴더
```

- 세 경로는 절대 경로로 준다. 출력 폴더는 아직 없는 경로여야 한다. 있으면 덮어쓰지 않고 멈춘다.
- 입력 폴더와 출력 폴더는 저장소 밖에 둔다. 입력에는 차량 ID가 들어 있다.
- 실행이 끝나면 임시 DB를 지운다.

## 설정 묶음 파일

`offline/settings-example.json`이 예시다. `sets`의 묶음마다 판정 전체를 한 번씩 돌리고, 출력 폴더 아래에 묶음 이름의 폴더를 만든다.

| 값 | 뜻 |
|---|---|
| name | 묶음 이름. 출력 폴더 이름이 된다 |
| horizonMinutes | 창 H(분) |
| coverageRule | 창 덮임 규칙. `NONE` 또는 `SECOND_BEYOND_WINDOW` |
| scanThreshold | 탐색 역치. 0이면 대기 위험이 계산된 모든 판정이 후보 조회까지 간다 |
| gainThreshold, alpha, maxWalkMinutes, maxDetour, kindOrder | 후보 비교에 쓰는 값. 후보를 연결하지 않은 지금은 결과에 영향이 없다. `ActionConfig`가 요구해서 적는다 |
| resolutionSeconds | 해상도(초) |
| epochBudget | 판정 기 예산. 오프라인에서는 후보 조회가 기준선을 바꾸지 않아 1이면 된다 |

여기 적는 값은 전부 측정용이다. 제품 값은 SAL-389에서 정하고, 이 파일의 값을 제품 값으로 옮기지 않는다. 결과 파일에도 같은 표지가 들어간다. 예시의 `gainThreshold`는 `ActionConfig`가 0보다 큰 값만 받아서 넣은 자리 값이다.

## 입력 폴더 규격

파일 이름은 `<이름>.csv` 또는 `<이름>.csv.gz`이고 첫 줄은 열 이름이다. PostgreSQL에서 아래처럼 뽑은 모양을 그대로 받는다.

```
COPY (SELECT <열 목록> FROM <테이블> WHERE <구간 조건>) TO STDOUT WITH (FORMAT csv, HEADER)
```

- 따옴표 없는 빈 칸은 NULL로 읽는다(COPY의 CSV 규칙과 같다).
- 열 순서는 자유다. 규격에 없는 열이 있거나 필수 열이 빠지면 적재를 멈춘다.
- `vehicle_observation.plate_number`(번호판)와 `seat_forecast.feature_snapshot`(예보의 특징 값)은 받지 않는다. 뽑을 때 열 목록에서 뺀다.
- 행의 ID는 원래 값을 그대로 쓴다. 테이블끼리 ID로 이어지기 때문이다.

| 파일 | 필수 | 필수 열 | 선택 열 |
|---|---|---|---|
| route | 예 | id, public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name | 없음 |
| route_version | 예 | id, route_id, content_digest, valid_from | valid_to, turn_sequence, up_first_departure_time, up_last_departure_time, down_first_departure_time, down_last_departure_time, 요일별 배차 간격 8열(weekday, saturday, sunday, holiday의 min, max) |
| route_stop | 예 | route_version_id, stop_order, stop_id, name, direction, boarding_allowed | 없음 |
| model_deployment | 아니오 | id, deployment_key, release_id, model_key, model_version, bundle_digest, prediction_target_version, calculation_version, supported_scope_digest, data_until, state | predecessor_deployment_id, activated_at, retired_at |
| observation_batch | 아니오 | id, route_version_id, scheduled_at, attempt_number, attempt_key, outcome, normalization_version, collection_strategy_version | requested_at, response_received_at, completed_at, http_status, result_code, failure_code, provider_rows, stored_rows, excluded_rows, input_confirmed_at, ingestion_origin |
| vehicle_observation | 아니오 | id, observation_batch_id, route_version_id, source_row_number, stop_order, stop_id, running_state, passed_stop_order | vehicle_id, vehicle_trip_key, remaining_seats, crowd_level, vehicle_type, route_type, tagless, seat_unknown_reason |
| forecast_publication | 아니오 | id, source_batch_id, route_version_id, observed_at, published_at, prediction_count | model_deployment_id, demand_statistics_revision, quality_revision, generated_at, provenance |
| seat_forecast | 아니오 | vehicle_observation_id, target_stop_order, route_version_id, stops_to_target, model_deployment_id, demand_statistics_revision, seat_full_chance_raw, seat_full_chance, generated_at, publication_id | expected_seats, quality_revision, evaluation_detail_required, seat_target, probability_target, label_version, inference_bundle_hash |
| arrival_snapshot | 아니오 | id, station_id, received_at, refresh_after_at, stale_at, payload | stored_at |
| route_version_first_publication | 아니오 | route_version_id, first_published_at | 없음 |
| decision | 아니오 | route_id, station_id, stop_sequence, decision_at (이 순서 그대로) | 없음 |

파일별로 알아 둘 점

- 필수가 아닌 파일이 없으면 그 테이블은 빈 채로 돈다. 예를 들어 도착 스냅샷이 없으면 모든 판정의 도착 목록이 "스냅샷 없음"이 된다.
- `route_version`: 편도 노선의 보드는 그 방향의 첫차, 막차 시각이 없으면 읽기를 거부한다(제품 동작). 운영에서 뽑을 때 선택 열도 함께 뽑는다.
- `model_deployment`: 판정 시각에 활성이던 배포는 activated_at과 retired_at으로 고른다. 두 열을 함께 뽑는다.
- `vehicle_observation`: 남은 좌석(remaining_seats)과 좌석 미제공 사유(seat_unknown_reason) 가운데 정확히 하나가 있어야 한다(DB 제약).
- `seat_forecast`: seat_target과 probability_target이 없으면 DB 기본값(FIRST_POST_STOP_OBSERVATION, FIRST_POST_STOP_ZERO)이 들어간다. 하차 뒤 좌석 계약(AFTER_ALIGHTING_ESTIMATE) 행은 label_version과 inference_bundle_hash가 있어야 하고, 특징 값은 실행기가 빈 객체로 채운다.
- `arrival_snapshot`: payload는 운영 테이블의 payload 열(jsonb) 그대로다. 제품 코드가 `ArrivalSnapshot`으로 읽는다. provider, operation, normalization_version, payload_digest는 실행기가 채운다.
- `ineligible_observation`, `route_data_quality`: 제품이 그 표와 예보 적격 뷰를 없앴다. 폴더에 이 파일이 있으면 적재를 멈춘다.
- `route_version_first_publication`: 그 판본의 첫 발행 시각이다. 입력에는 판정 구간의 발행만 들어 있어서, 이 파일이 없으면 구간 안에 발행이 없는 판본을 "발행이 한 번도 없던 판본"과 구분하지 못한다. 제품은 발행이 있던 판본에서 최근 발행 묶음이 없으면 최근 관측 없음으로 거부하고, 발행이 한 번도 없던 판본에서는 예보 없이 가장 늦은 묶음을 보여 준다. 실행기는 이 파일의 시각에 예보 없는 표시용 발행을 넣어 제품과 같은 쪽으로 읽는다. 파일이 없어 발행 없는 판본으로 읽힌 판정은 `diagnostics.boardAtDecision`에 `READ_UNPUBLISHED`로 따로 센다.
- 폴더에 `INCOMPLETE` 파일이 있으면 추출이 중간에 멈춘 폴더다. 실행기는 그 폴더를 읽지 않는다.
- `decision`: 판정할 자리를 직접 적는다. route_id는 public_route_id이고 decision_at은 `2026-11-10T00:00:00Z` 같은 ISO-8601 시각이다. 이 파일이 있으면 적힌 자리만 판정한다.

구간을 잡을 때: 판정 구간을 [S, E]라 하면 관측 묶음과 차량 관측, 예보 발행, 좌석 예보는 S의 10분 전부터 뽑는다. 보드가 판정 시각의 10분 전까지의 묶음을 보기 때문이다.

## 판정할 자리

- `decision` 파일이 있으면 그 자리를 그대로 쓴다.
- 없으면 도착 스냅샷에서 만든다. 스냅샷마다, 그 수신 시각에 그 정류장을 지나던 노선 방문 가운데 승차할 수 있는 방문마다 한 건이다. 판정 시각은 스냅샷의 수신 시각이다. 사용자가 실제로 판정을 받는 시각(수신 뒤 0초에서 60초 사이)과 다를 수 있다. 스냅샷에 그 노선의 행이 없어도 만든다(도착 목록이 빈 판정이 된다).

연속한 스냅샷의 판정들은 같은 버스를 여러 번 본다. 건수를 독립 표본 수로 읽지 않는다.

## 판정 시각 T 기준으로 읽는 규칙

| 읽는 것 | 규칙 | 제품과 다른 점 |
|---|---|---|
| 정류장의 노선 방문 | T에 유효하던 노선 판본(valid_from <= T, valid_to가 비었거나 T보다 뒤) | 제품은 지금 판본(valid_to가 빈 것) |
| 도착 스냅샷 | received_at <= T인 것 가운데 가장 최근 것 | 제품은 시각 조건 없이 가장 최근 것 |
| 보드의 관측 묶음 | T까지 발행된(published_at <= T) 묶음 가운데, 수신 시각이 T의 10분 전부터 T 사이이고 가장 늦은 것. 발행이 하나도 없는 판본은 같은 구간의 가장 늦은 정상 묶음 | 제품은 지금 DB에 있는 발행을 본다 |
| 활성 모델 | activated_at <= T이고 retired_at이 비었거나 T보다 뒤인 배포. activated_at이 빈 ACTIVE 배포는 언제나 활성으로 본다 | 제품은 state가 ACTIVE인 배포 |
| 묶음의 정류장, 예보, 차량 | 묶음 ID와 판본 ID로 읽는다 | 같다 |
| 신선도 | 제품의 `BoardFreshnessPolicy`, `ArrivalStopSource`에 T로 맞춘 시계와 판정 시각을 준다 | 같다 |

시계를 지금에 두면 실행기 구현과 제품 저장소 구현이 같은 값을 돌려준다. `AsOfReadContractTest`가 같은 DB에서 둘을 비교한다.

되살리지 못하는 것

- 보드는 그 묶음의 좌석 예보를 전부 읽는다. 예보 적격 뷰와 편도 제외는 쓰지 않는다.
- 재발행으로 예보 행이 덮였으면 덮인 뒤의 값을 읽는다. 재발행은 발행 시각(published_at)도 재발행 시각으로 덮는다(`JdbcForecastPublicationRepository`의 갱신 문). 그래서 원래 발행과 재발행 사이의 판정 시각에서는 그 묶음이 아직 발행 전으로 보이고, 실행기는 더 앞의 묶음을 고른다.
- 도착 스냅샷이 수신된 뒤 DB에 저장되기까지의 지연은 되살리지 않는다.
- `BoardQueryService`의 읽기 트랜잭션(반복 읽기)은 걸지 않는다. 임시 DB는 적재 뒤 바뀌지 않아 결과에 영향이 없다.

## 실제 결과 대조

판정 시각 뒤의 차량 관측에서 실제 결과를 읽는다. 판정 입력에는 쓰지 않는다.

시도 한 건(도착 항목의 차량 한 대)의 결과

- 그 차량의 판정 시각까지의 마지막 관측에서 시작해 뒤 관측을 시각 순서로 따라간다.
- 첫 관측 기준: 통과 순번이 그 정류장 순번과 같은 첫 관측의 남은 좌석. FIRST_POST_STOP_ZERO 예보의 정산(`ArrivalLabelResolver`)이 고르는 관측이다.
- 마지막 관측 기준: 통과 순번이 그 정류장 순번 이상이고 다음 승차 정류장 순번 미만인 관측 가운데 마지막의 남은 좌석. 다음 승차 정류장을 지난 관측이 있어야 확정한다. DEPARTURE_ZERO 예보의 정답을 흉내 낸 근사다. 제품(`LesPassEvents`)은 모델 번들의 정차 순번으로 다음 정류장을 정하고 5순번까지의 후퇴를 허용하는데, 실행기는 route_stop의 승차 가능 정류장을 쓰고 순번이 되돌아가면 끊는다.
- 남은 좌석이 1 이상이면 `SEATS_AVAILABLE`, 0이면 `FULL`, 그 밖은 `UNKNOWN/<이유>`다.
- 추적을 끊는 조건은 제품 정산(`ArrivalLabelResolver`)을 따른다. 연속 관측 간격이 90초를 넘거나, 통과 순번이 되돌아가거나, 운행 방향이 출발점과 다르면 `LOST`다. 방향은 `operational_observation.quality_direction`이다. 판정 뒤 2시간까지만 본다.
- 제품 정산과 다른 점: 제품은 예보의 원 관측에서 시작해 예보 계산 시각 뒤의 관측만 보는데, 시도의 결과는 판정 시각에서 시작한다(예보가 아니라 판정이 기준이다). 품질 판정을 아직 받지 않은 관측에서 기다리는 규칙은 쓰지 않는다. 보드 차량 진단(`boardVehicleOutcomes`)은 제품처럼 보드 묶음의 관측에서 시작하고 예보 계산 시각까지의 관측을 건너뛴다.

| 이유 | 뜻 |
|---|---|
| NO_VEHICLE_ID | 도착 항목에 차량 ID가 없다 |
| NO_VISIT | 판정 시각에 그 노선 방문을 찾지 못했다 |
| NO_OBSERVATION | 그 차량의 관측이 입력에 없다 |
| ALREADY_PASSED | 판정 시각의 관측으로는 차량이 이미 그 정류장을 지났다 |
| NOT_REACHED | 2시간 안에 그 정류장에 닿는 관측이 없다 |
| SKIPPED | 그 정류장 순번을 건너뛰었다 |
| LOST | 추적이 끊겼다 |
| SEAT_MISSING | 그 관측에 좌석 값이 없다 |
| NOT_CONFIRMED | (마지막 관측 기준만) 다음 승차 정류장을 지난 관측이 없다 |

대기 판정 한 건의 성공 여부

- 시도열 기준(주): 판정이 대기 위험 계산에 쓴 시도의 차량 가운데 한 대라도 `SEATS_AVAILABLE`이면 `SUCCESS`, 전부 `FULL`이면 `FAIL`, 그 밖은 `UNKNOWN`, 시도가 없으면 `NO_ATTEMPT`다. 첫 관측 기준과 마지막 관측 기준을 따로 낸다.
- 창 기준(보조): 판정 시각 뒤 창 H 안에 그 정류장을 떠나기 시작한 그 노선의 차량마다 첫 관측의 좌석을 본다. 한 대라도 1석 이상이면 `SUCCESS`, 전부 0석이면 `FAIL`, 그런 차량이 없으면 `NO_PASSAGE`다. 차량 한 대는 한 번만 센다. 판정 시각에 이미 그 정류장을 떠난 상태였던 차량은 넣지 않는다. 그 관측이 바로 앞 관측과 이어지지 않으면(90초 초과, 방향 다름, 적격 아님) 좌석을 모름으로 둔다. 첫 관측 기준만 낸다.
- 위험 구간별 실패율(`failureByRisk`)에는 차량의 결과를 전부 아는 판정만 넣는다. 성공은 한 대만 빈자리여도 정해지고 실패는 전부 만석이어야 정해져서, 일부만 아는 판정을 넣으면 실패율이 낮게 나온다. 뺀 판정은 `excludedDecisions`에 종류별로 센다(`SEATS_AVAILABLE_AND_UNKNOWN`, `FULL_OR_UNKNOWN_ONLY`, `NO_VEHICLE`).
- 시도 단위 보정표와 두 시도의 만석 동반 집계는 결과를 아는 건만 센다. 어느 결과든 모르면 똑같이 빠지므로 위와 같은 쏠림은 없다. 다만 추적이 끊기는 일이 만석 여부와 무관하다는 보장은 없다.

"출발 뒤 빈자리 1석 이상"은 줄 맨 뒤 사람이 탔다는 것의 충분조건이고 필요조건은 아니다. 만석 출발이어도 마지막 좌석에 맨 뒤 사람이 앉았을 수 있다.

## 결과 파일

출력 폴더 아래 묶음마다 두 파일이 생긴다. 두 파일 모두 차량 ID, 번호판, 원자료 행을 담지 않는다.

`summary.json`

| 블록 | 내용 |
|---|---|
| notice, candidates | "측정용 값이며 제품 값이 아님", "후보 미연결" |
| codeCommit | 실행한 코드의 커밋. 커밋하지 않은 변경이 있으면 `+uncommitted`가 붙는다 |
| settings, settingsNotUsedInThisRun | 쓴 설정 값 전부와, 이번 실행에서 결과에 영향이 없는 값의 이름 |
| data | 테이블별 적재 행 수, 판정 자리의 출처와 건수, 판정 시각과 자료의 시각 범위, 확률 목표별 좌석 예보 수 |
| results.reasonCodes | 사유 코드별 판정 수 |
| results.arrivalLists, entriesPerList, attemptsPerDecision | 도착 목록의 상태, 목록의 항목 수, 창 안 시도 수의 분포 |
| results.stayEvaluations, stayGaps | 대기 평가의 상태(평가 가능, 확인된 공집합, 근거 부족)와 Gap의 종류, 목록 역할, 결측 사유별 수 |
| results.stayAssumptions, stayFailureRisk | 평가 가능한 대기 결과의 가정 표지 분포와 실패 위험의 분위수(가장 가까운 순위 방식) |
| results.seatForecastLookups | 도착 항목마다 좌석 예보 확률을 찾았는지. 찾았으면 확률 목표별, 못 찾았으면 원인별. `allEntries`는 목록의 모든 항목, `windowAttempts`는 대기 위험 계산에 들어간 시도만 센다 |
| outcomes.attempts | 대기 위험 계산에 들어간 시도의 실제 결과. 기준별 결과 수, 근사 확률 구간별 `SEATS_AVAILABLE` 비율(보정표), 첫 관측과 마지막 관측의 좌석 차이 |
| outcomes.stayByAttempts, stayByWindow | 대기 판정의 성공 여부 분포와, 예측한 대기 실패 위험 구간별 실제 실패율(차량의 결과를 전부 아는 판정만. 뺀 판정 수를 함께 낸다) |
| outcomes.firstTwoAttempts | 시도가 둘 이상인 판정에서 앞의 두 시도의 결과를 모두 알 때, 둘 다 `FULL`인 건수와 근사 확률의 곱으로 예측한 건수(`bothFullPredictedByProduct`는 (1 - 앞차 확률) x (1 - 뒤차 확률)의 합) |
| diagnostics.boardVehicleOutcomes | 판정 입력도 판정 결과도 아닌 진단. 판정 시각의 보드가 그 정류장 방문에 담은, 예보가 붙은 차량 한 대를 단위로 예보 값과 실제 결과를 맞춘다. 실제 결과는 보드의 관측 묶음 시각 뒤에서 찾는다. 같은 차량과 정류장이 판정 시각마다 다시 세어져 독립 표본이 아니다(`distinctVehicleStopPairs`가 서로 다른 차량, 정류장 쌍의 수다) |
| diagnostics.boardAtDecision | 판정 입력도 판정 결과도 아닌 진단. 판정 시각에 그 노선의 보드를 읽을 수 있었는지(`READ` 또는 보드가 거부한 오류 코드), 읽었다면 그 정류장 방문에 담긴 차량 수와 예보가 붙은 차량 수의 분포. `READ_UNPUBLISHED`는 그 판본에 그때까지 발행이 없어 예보 없는 보드였다는 뜻이다. 도착 목록이 비어 제품 코드가 보드를 읽지 않은 판정에서도 센다 |

값 없음 원인은 `SeatForecastProbabilitySource.Cause` 그대로다. `VEHICLE_NOT_ON_BOARD`는 실행기가 같은 관측 묶음을 다시 읽어 속 원인으로 나눈다.

| 속 원인 | 뜻 |
|---|---|
| NOT_IN_BATCH | 그 관측 묶음에 이 차량의 관측이 없다 |
| ALREADY_PASSED | 관측으로는 차량이 이미 이 정류장을 지났다 |
| BEYOND_HORIZON | 차량이 12정류장보다 멀리 있다 |
| INCONSISTENT_ORDER | 차량의 정류장 순번과 통과 순번의 관계가 맞지 않아 보드가 뺐다 |
| BEYOND_NEAREST | 담을 수 있는 차량이지만 보드가 가까운 순서로 담는 대수 밖이다 |
| STOP_NOT_BOARDABLE | 승차할 수 없는 정류장이라 보드가 차량을 담지 않는다 |
| UNEXPLAINED | 위 어느 것도 아니다. 0이 아니면 실행기의 조건이 제품과 어긋났는지 확인한다 |

후보를 연결하지 않아 사유 코드는 세 가지만 나온다. `ABSTAIN_STAY_UNDEFINED`(대기 위험을 계산하지 못함), `STAY_SCAN_NOT_TRIGGERED`(대기 위험이 탐색 역치보다 낮음), `NO_MOVE_NO_CANDIDATE`(대기 위험이 탐색 역치 이상이라 후보를 조회했을 판정)다.

`decisions.csv`

판정 한 건당 한 줄이다. 열은 route_id, station_id, stop_sequence, decision_at, reason_code, arrival_list, entries, attempts, stay_evaluation, stay_failure_risk, stay_assumptions, stay_gaps, entry_lookups, board, board_vehicles, board_vehicles_with_forecast, entry_outcomes_first, entry_outcomes_last, stay_outcome_by_first, stay_outcome_by_last, stay_outcome_by_window다. 한 칸에 여러 값이 있으면 `|`로 잇는다. entry_lookups는 `순서:결과` 모양이고 순서는 도착이 이른 순서다. 순서 뒤의 `*`는 대기 위험 계산에 들어간 시도라는 표시다. 역치를 바꿔 가며 사유 코드 분포를 다시 계산할 때 stay_failure_risk 열을 쓴다.

## 검증

- `AsOfReadContractTest`: 판정 시각 기준 읽기와 제품 저장소의 대조, 과거 시각의 읽기 규칙
- `SalmonOfflineRunTest`: 합성 입력 폴더를 임시 DB에 넣고 실행기를 끝까지 돌려 집계와 줄 단위 결과를 확인
- `PassOutcomeTest`: 실제 결과를 찾는 규칙. 제품 정산의 테스트(`ArrivalLabelResolverTest`)와 같은 입력에 같은 결과를 내는지 본다
- `CsvRowsTest`, `RunSettingsTest`: CSV 읽기 규칙, 설정 파일 읽기
- `docs/salmon-action/scripts/preservation_cost.py --self-test`: 보존 견적, worker의 현재 보존 값, 추출 열, 출퇴근 시각의 중단. `ArrivalRateLimitDrainTest`는 같은 버킷을 Java로 센다.

합성 입력은 테스트 코드(`SyntheticInput`)가 만드는 값이고 실제 노선이나 차량의 값이 아니다.
