# SAL-359: 20개 노선 모델의 Worker 입력과 정산 연결

기준 커밋: `8a33b45b`. 이 문서는 2026-11-05 로컬 검증 기록이다. 운영 DB 변경, 배포, push는 수행하지 않았다.

## 무엇을 맞췄나

20개 노선 학습은 당시 `input_confirmed_at`까지 확인된 관측으로 입력을 만들었다. 기존 Java는 `response_received_at`을 사용했고, 입력 확인 시각도 예보 저장 시 다시 기록했다. 그대로 쓰면 학습과 운영의 관측 범위가 달라지고 재처리할 때 같은 입력의 시각도 바뀐다.

새 판본은 예측 계산 전에 별도 트랜잭션으로 입력 확인 시각을 한 번만 저장한다. 기존 수집 API도 원자적 갱신으로 첫 시각을 보존한다. 예보 저장이 실패해도 확정 시각은 남으므로 같은 배치를 다시 계산할 수 있다.

| 구분 | 기존 판본 | 새 판본 |
|---|---|---|
| 입력 판본 | `les-original-v1` | `les-confirmed-closure-v2` |
| 입력에서 아는 시각 | 수신 시각 | 입력 확인 시각 |
| 관측 연결 | 기존 허용 후퇴와 간격 | 노선 버전, 방향, 후퇴, 확인 간격과 수신 간격으로 구분 |
| 당일 수요와 대기 상태 | 정류장 통과 시각 | 통과를 확인한 시각 |
| 정산 판본 | `les-identity-safe-v1` | `les-confirmed-routeband-v2` |
| 정산 정원 | 노선 버전과 당일 관측 최대 | 같은 차량의 과거 최대 좌석을 방문 확인 시점까지 누적 |
| 정산 시간대와 배차 간격 | 기존 첫 출발 관측 | 학습과 같은 마지막 출발 관측 |

새 판본은 LIVE 수집, SUCCESS_ROWS, 양수 저장 행 수, 확인 지연 0~90초인 배치만 사용한다. 익명 차량과 같은 배치의 중복 차량 행은 제외한다. 좌석 미관측은 0으로 바꾸지 않는다. 승차 가능한 정류장을 대상으로 같은 방향의 최대 12개 정류장까지 입력을 만들며, 평일 출퇴근 시간 지원 범위는 유지한다.

기존 판본의 생성자, 입력 계산과 정산 기록은 보존한다. 새 체크포인트는 별도 하위 디렉터리에 저장하고 다른 판본을 재사용하지 않는다. 예보에는 입력 벡터, 차량 최대 좌석의 근거 관측 ID, 입력 판본과 정산 판본을 저장한다.

## DB 변경

`V49__les_input_provenance.sql`은 `observation_batch.ingestion_origin`을 정식 스키마에 반영한다. 이 열이 이미 있는 DB에서는 기존 값을 유지한다. 과거 출처 미상 행과 수동 입력의 기본값은 UNVERIFIED이며, 새 실시간 수집 어댑터가 LIVE를 명시한다. 과거 행을 일괄 LIVE로 간주하지 않는다. 열이 없던 DB와 이미 있던 DB를 로컬 PostgreSQL에서 각각 검사했다.

## 검증 결과

최종 실행 결과는 아래와 같다. 검증 도구는 `verifyLesWorkerReplay`이고 격리된 로컬 PostgreSQL에서 실제 `JdbcLesFeatureQuery` → `ForecastBatchWriter` → `ForecastBatchCommitter` → `JdbcLesForecastEvaluation`을 실행한다. 운영 연결을 사용하지 않는다.

- 집중 테스트: forecasting 129개, observations 29개, common 9개, 총 167개 통과. 전체 CI 재실행은 사용자 요청으로 생략했다.
- 20개 노선 도메인 입력 비교: 실제 표본 220건의 좌석 입력 88열과 만석 확률 입력 80열이 Python과 전부 일치했다.
- 전체 학습 표본 1,173,470건의 방문 확인 시점 점검: 원본 관측 후 2시간을 넘겨 확인된 표본 0건. 최대 약 96분이었다. 앞으로 수집 지연이 더 길어지는 경우까지 보장하는 결과는 아니다.
- 수치 계산 검증: 실제 입력 2,560건과 합성 경계 입력 481건, 총 3,041건에서 Python 결과와 대조했다. 좌석의 최대 차이 7.105427357601002e-15석, 출발 만석 확률의 최대 차이 2.220446049250313e-16이었다.
- 전체 Worker/정산 최종 재생: 20개 노선, 실제 표본 220건, 정산 목표 440건 전부 통과했다. 입력과 DB 저장 예측값의 최대 차이는 0이었다. 정산값 최대 차이는 4.014566457044566e-13석이었다.
- 정산의 전/후/방문 확인 관측 ID와 확인 시각이 Python과 일치했다. 하차 후 좌석 목표는 직전 차량의 출발 관측 ID, 정원 값과 정책도 일치했다. 같은 배치를 두 번 발행해도 최초 확인 시각을 유지했다.
- 큰 재생 실행은 JVM 힙 최대 1GiB와 ActiveProcessorCount=1로 수행했다. 도중 측정한 Java RSS는 약 1.3GiB였다. JVM 설정이 프로세스 전체 메모리 상한을 뜻하지 않는다. 최종 전체 재생은 Gradle 기준 2분 58초였다.

## 모델 파일과 재실행

학습한 가중치는 바꾸지 않았다. 원래 연구 번들에서 입력과 정산 계약만 명시한 별도 비활성 번들을 만들었다. `base.json`, `gate.json`, `pool.json`, `constants.json`은 원본과 바이트 단위로 같다.

- 원래 연구 번들 manifest SHA-256: `1a6b05b89a46d539d613f2586ad81e89f149f8b61a827d1393a646b42989ab84`
- Worker 계약 번들 manifest SHA-256: `ebf96ce1836ae2347027803918bad2de9209c36da0879a359bb9948b6be4a220`
- 로컬 자료 루트: `/Users/goo/.local/share/codex/salmonbus-model-handoff-20261104/causal-20routes-20261105`
- 계약 번들: `worker-bundle-20261105`
- 재생 입력: `worker-replay-evidence-20261105` (실제 11월 4일 관측 598,651행, 이전 자료에서 만든 자정 상태와 정원 근거 포함)
- 최종 재생 출력: `worker-db-final-v2-20261105`
- 집중 테스트 로그: `worker-focused-final-v2.log`, 최종 서식 반영 후 재검증 `worker-precommit-final.log`
- 최종 재생 로그: `worker-db-final-v2.log`

```sh
# backend에서 실행. FIXTURES/BUNDLE/OUTPUT은 자료를 받은 팀원의 절대 경로로 지정한다.
# OUTPUT은 새 디렉터리여야 한다. 테스트용 Docker가 필요하다.
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock nice -n 10 ./gradlew \
  :forecasting:verifyLesWorkerReplay \
  -PlesWorkerFixtures="$FIXTURES" -PlesBundle="$BUNDLE" -PlesWorkerOutput="$OUTPUT" \
  --no-daemon --no-parallel --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1g -Xms128m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=1'
```

`backend/tools/les_model/prepare_causal_runtime.py`로 비활성 연구 번들에서 계약 번들을 만들고, `causal_worker_fixture.py`로 원본 관측과 Python 기대 입력/정산을 만든다. 해시가 바뀐 입력은 Java 재생 도구가 거부한다. 큰 원자료와 fixture는 Git에 넣지 않는다.

## 확인 범위와 남은 작업

이 재생은 기존에 추출한 실제 관측을 로컬 DB에 넣고 Worker가 사용하는 발행과 정산 코드를 호출한다. 스케줄러의 실시간 주기, 외부 수집 통신, 운영 DB 부하는 측정하지 않았다. 자정 이전 상태는 Python에서 재구성한 체크포인트를 사용한다. Java의 체크포인트 생성과 재기동은 별도 DB 테스트로 확인했으며, 전체 과거 DB를 처음부터 Java로 다시 읽은 실험은 아니다.

운영 품질 판정 이력은 추출 자료에 없다. 선택한 원본 관측에는 테스트용 확정 식별을 부여했고, 다른 행은 기존 운영 뷰의 미할당 행 허용 규칙을 따른다. 따라서 당시 운영에서 어느 차량이 품질 사유로 제외됐는지까지 같다고 주장할 수 없다.

하차 후 좌석 정답은 직접 잰 승하차 인원이 아니라 전후 좌석 관측과 노선/시간대별 공유 계수로 계산한 추정값이다. 출발 만석의 정산은 관측된 출발 좌석 0 여부다. 기존 정류장별 계수 모델과 이 판본을 혼용하지 않는다.

번들의 `serving_ready=false`를 유지한다. 실제 운영 활성화 검증과 기존 8개 노선 활성화 허용 범위의 확장은 아직 하지 않았다. 이 작업의 결과는 20개 노선 모델의 로컬 Worker 입력/정산 일치 검증이며, 운영 모델 전환 완료를 뜻하지 않는다.

## 최종 재검증과 검토

서식 정리 뒤 `worker-precommit-final.log` 실행도 통과했다. 집중 테스트 167개와 새 계약 번들 수치 비교 3,041개, 20개 노선 도메인 입력 비교를 같은 작업 트리에서 순차 실행했다. Worker 전체 재생 이후의 변경은 Java 공백, 원시 지역 변수 final, 필드 선언 순서와 이 문서 정리이며 계산이나 SQL은 바꾸지 않았다.

집계 결과는 같은 디렉터리의 `20-route-worker-replay-result-20261105.json`에 보존한다. BE Java 컨벤션은 Confluence 52723720의 v6 본문으로 확인했다. 여러 필드를 가진 정산 객체는 한 노선/날짜의 관측과 사건 캐시를 공유하는 하나의 정산 책임을 갖는다. 긴 SQL과 JSON 문자열은 원문을 보존하고 일반 Java 코드의 긴 줄을 나눴다.

입력 확정, 스키마와 정산 근거가 같은 발행 계약으로 연결되어 있으므로 한 커밋으로 묶었다. 전체 모듈의 테스트 개수 기준은 이번에 작성한 테스트 수만 반영했으며, 전체 CI를 실행해 재측정한 값은 아니다.
