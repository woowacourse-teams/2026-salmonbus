# Les 정산과 보관 계약

예보의 좌석 수는 **하차 후 추정 좌석**, 확률은 **출발 때 잔여 0석일 확률**이다. 같은 숫자로 평가하지 않는다. 두 목표의 작업·결과는 `les_forecast_evaluation_*`에 분리한다. 기존 정산 이력, 정수형 도착 좌석, `DemandSample`, 당일 확률 보정 통계는 변경하지 않는다.

## 예보부터 결과까지

1. `seat_forecast`를 저장하는 트랜잭션에서 DB trigger가 두 목표 작업을 만든다.
2. 기존 Worker 정산 tick이 Les 평가도 호출한다. 작업은 30초 후 재시도하도록 옮겨 뒤의 예보를 막지 않는다. 재시작하면 DB 작업에서 계속한다.
3. 기준 시각은 계산한 시각이 아니라 원관측 배치의 수신 시각이다. 과거 데이터를 늦게 재생해도 기준 날짜가 바뀌지 않는다.
4. 같은 차량·실제 편도 키·방향의 관측만 결과로 연결한다. 마지막 도착 전 관측과 마지막 출발 후 관측을 선택한다. 다음 실제 정류장 통과를 관측해야 마지막 출발 후 관측이 확정된다.
5. 출발 0석은 0/1로 평가한다. 하차 후 좌석은 번들에서 발행 시 복사한 정류장·시간대 계수로 추정한다. 결과는 소수를 유지한다.
6. 두 목표의 결과 삽입과 각 작업 삭제는 하나의 트랜잭션이다. 같은 키·품질 판본을 재시도해도 중복 정산하지 않는다.

실제 노선별 관측에서 계산한 값을 이 문서에 새로 주장하지 않는다. 구현 테스트는 만든 관측과 별도로 표시한 Python 수식 참조값을 사용한다.

## 원본 연구와 같은 부분, 달라진 부분

`LesAlightingPosterior`는 `alight_fit.py`의 이항 하차 분포와 음이항 승차 분포를 곱해 조건부 평균을 계산한다. 계수 `p`, `lam[8]`, `kappa`, `r`를 그대로 사용한다. 새 계수를 학습하거나 낙관 위험에 맞춰 수치를 보정하지 않는다.

운영 라벨 판본은 `les-identity-safe-v1`이다. 다음은 원본 연구의 과거 라벨과 다르다.

- 연구의 시간 구간 연결 대신 실제 차량·편도·정류장을 확인한다.
- 연구의 전체 자료에서 구한 차량 최대 좌석 대신, 해당 노선·당일·라벨 확정 시각까지 관측한 차량 최대 좌석을 사용한다. 결과에 `observed-route-day-max-through-closure-v1`, 용량 값, 근거 관측을 남긴다. 이 값은 실제 정원이라는 보장이 없다.
- 확정되지 않은 마지막 정류장 구간, 90초 초과 관측 단절, 동일 편도 근거 부족은 연결하지 않는다.
- 계수가 없는 정류장/시간대, 주말/원본 fit 제외 공휴일, 선행 차량 근거 부족, 지원 범위 밖 배차 간격, 30석 이상 용량 관측 부족은 하차 후 좌석을 `UNSUPPORTED`로 기록한다. 출발 0석 목표는 별도로 완료할 수 있다.
- 원관측에서 2시간이 지나도 연결 근거가 없으면 `LOST`로 남긴다. 0석으로 대체하지 않는다.

따라서 Java의 추론·posterior 계산 수식 일치와, 기존 연구 자료의 모든 라벨 재현은 다른 검증이다.

## 품질 재평가와 조회

결과 키에는 관측, 목표 정류장, 목표 종류, 라벨 판본, 품질 판본이 들어 있다. 품질 판본이 바뀌면 새 작업을 등록하고 이전 결과를 보존한다. 현재 유효 성적은 `les_quality_eligible_metric`에서 조회한다. 모든 목표를 저장하므로 기존 상세 결과 표본화 비율에 성적이 좌우되지 않는다.

원관측 당시 고정된 `feature_snapshot`, `inference_bundle_hash`, 모델 배포 ID로 사용한 입력과 계수를 식별한다. 같은 원관측을 다른 모델로 재발행해 과거 결과를 덮어쓰는 방식은 지원하지 않는다.

## 보관과 복원

기존 정수 정산의 S3 형식과 분리해 `les-evaluation-archive-v1`을 사용한다. `forecast.archive.enabled`, `forecast.archive.delete-enabled`의 기존 운영 설정을 따른다. 레스 전용 prefix는 `salmonbus-be/les-evaluation-archive/v1/`이다.

- 결과 원본과 도착 전·출발 후·구간 확정·선행 차량·용량 근거의 관측 및 배치 snapshot을 함께 저장한다.
- SHA-256과 실제 다운로드 내용을 검증하고, DB 원본과 근거가 그대로인지 다시 확인한 후 삭제한다. 전송 실패 시 원본을 삭제하지 않는다.
- 삭제 후에도 작은 성적 행은 DB에 남는다. `les_forecast_evaluation_metric`에서 live+보관 성적이 중복 없이 조회된다.
- 삭제가 끝나면 새 라벨의 근거 관측 FK를 유지하지 않는다. 다른 기존 FK나 보관 정책까지 해제하지는 않는다.
- 원자료 삭제 후 복원은 라벨·판본·수치를 복원하고, 관측 FK 대신 `evidence_archive_hash`로 검증된 보관 파일을 참조한다. 예보 대기나 수요 통계를 다시 만들지 않는다.
- 복원 API는 `LesEvaluationArchive.restore(observationId, stop, target, labelVersion, qualityRevision)`이다. 운영자는 [les-evaluation-restore 명령](evaluation-restore.md)으로 다운로드한 체크섬 원본과 정확한 결과 키를 지정해 복원할 수 있다. ACADEMY DB 쓰기는 전용 승인 자료가 필요하다.

보관은 기존 `forecast.archive.page-size`를 따라 tick당 최대 1~1000개 결과를 한 파일로 묶는다(기본 500개). 파일은 16MiB 이하이며 초과하면 삭제하지 않고 페이지 크기를 줄여야 한다. 삭제 설정을 나중에 켜도 이미 검증한 묶음을 재사용한다. 실제 유입량 대비 처리량은 운영 활성화 전에 측정해야 한다.

## 자원과 미검증 범위

정산은 tick의 대상 노선별 최대 100개 목표를 읽는다. 자정부터 평가창 끝까지 읽는 관측 한도는 `forecast.les.evaluation.maximum-observation-rows`로 설정한다(기본 50,000개, 허용 1~1,000,000개). 한도를 늘리는 것은 메모리와 조회량 증가를 검토한 운영 결정이며 자동으로 늘리지 않는다. 초과는 `UNSUPPORTED / OBSERVATION_INPUT_LIMIT`로 종료한다. 시간이 지났다는 이유로 일반 관측 연결 실패 `LOST`로 바꾸지 않는다. 실제 운영 노선의 하루 관측량과 tick 지연은 아직 이 문서의 테스트로 측정하지 않았다. 기존 source/forecast 보존 정책, 네트워크/S3 IAM, 운영 스케줄 간 부하도 실제 배포 전에 별도로 확인해야 한다.

## 로컬 검증 기록

2026-11-04 부모 작업자의 순차 실행에서 `JdbcLesForecastEvaluationTest` 11개와 `LesPublicationLifecycleTest` 1개가 실제 PostgreSQL로 통과했다. 앞선 회차에서는 `LesAlightingPosteriorTest` 3개와 `LesPassEventsTest` 4개가 통과했다. 원격 S3 전송 대신 파일 생성 방식까지 같은 메모리 객체 저장소를 사용했다. 실제 S3 권한·전송 성능을 검증한 결과는 아니다.

검증 중 DB 예약 시각과 앱 시계의 차이로 첫 작업 조회가 비는 문제가 확인됐다. 예약·조회·재시도는 DB `clock_timestamp()`로 통일했고, 관측의 기준 시각과 평가 시각은 별도로 유지했다. 실제 S3 구현이 미존재 파일을 요구하는 계약도 테스트 저장소에 반영했다.


## 입력 부족과 보관 상태 확인

`les_evaluation_status`에서 다음을 구분한다. 이 뷰는 품질 판본별 이력을 포함하므로 현재 성적만 계산할 때는 `les_quality_eligible_metric`을 사용한다.

| 상태 / 이유 | 뜻 |
|---|---|
| `PENDING / QUALITY_REBUILD_PENDING` | 노선 품질 조사 중. 조사 종료까지 보류한다. |
| `PENDING / NO_CLOSED_IDENTITY_SAFE_PASS` | 해당 편도의 구간 확정 관측을 기다린다. |
| `UNSUPPORTED / OBSERVATION_INPUT_LIMIT` | 설정한 조회 상한을 넘었다. 관측 성공/실패 성적에 넣지 않는다. |
| `UNSUPPORTED / SOURCE_HISTORY_INCOMPLETE` | 수집 배치의 저장 건수와 현재 원자료 건수가 달라 일부 원자료가 부족하다. |
| `UNSUPPORTED / SOURCE_HISTORY_UNVERIFIED` | 성공 배치의 저장 건수 기록이 없어 원자료 완전성을 확인할 수 없다. 삭제 사실을 단정하지 않는다. |
| `UNSUPPORTED / SOURCE_QUALITY_EXCLUDED` | 조사 중인 상태는 아니지만 예보 원관측이 품질 조건에서 제외됐다. |
| `LOST / NO_CLOSED_IDENTITY_SAFE_PASS` | 위 입력 문제 없이 2시간 내 동일 편도 구간을 연결하지 못했다. |

종료 이유는 이관 compact에도 남는다. `retention_state`는 `WORK_PENDING`, `LIVE_UNARCHIVED`, `VERIFIED_LIVE`, `ARCHIVED`, `RESTORED`를 구분한다. 예를 들어 다음 조회로 입력 상한 초과와 보관 대기량을 분리할 수 있다.

```sql
SELECT route_version_id, target_kind, scoring_state, reason, retention_state, count(*)
FROM les_evaluation_status
GROUP BY route_version_id, target_kind, scoring_state, reason, retention_state;
```

설정 변경만으로 이미 종료한 결과를 자동 재시도하거나 덮어쓰지 않는다. 실제 품질 재평가가 발생하면 새 품질 판본으로 다시 계산하고 이전 지원불가 결과를 보존한다. 입력 한도 조정만을 이유로 과거 결과를 재시도하는 운영 명령은 아직 추가하지 않았다. 배치 행 자체까지 수동 삭제돼 비교 기준이 없어진 경우는 이 건수 검사만으로 찾을 수 없다. 전체 원자료 보존 장부를 새로 만든 것은 아니다.
