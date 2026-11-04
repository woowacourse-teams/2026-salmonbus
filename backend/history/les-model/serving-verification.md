# 운영 번들 전환에 필요한 실제 검증

현재 코드 연결과 운영 활성화 가능 여부는 다르다. `prepare_serving_bundle.py`는 이미 만들어진 검증 receipt와 증거 파일의 형식·출처 해시를 검사해 묶는 포장 도구다. 검증을 실행하거나 증거의 진위를 판정하는 도구가 아니다. 원본 보고서를 읽어 판정을 계산하고 receipt를 만드는 자동 실행기는 아직 없다. 따라서 이 명령이 파일을 받아들였다는 사실만으로 운영 검증 완료를 선언하면 안 된다. 현재 로컬 연구 번들의 `serving_ready=false`는 유지한다.

## 보고서에서 receipt로 옮길 때의 제약

- 원본 보고서의 문자열 `status=PASS`를 그대로 포장하거나, 비어 있지 않은 JSON 파일 하나를 증거로 넣어서는 안 된다. 검증 명령·코드 판본·원자료 범위·비교 기준·사례별 결과를 읽고 실제로 기준을 만족했는지 확인해야 한다.
- source manifest SHA-256은 비교에 실제 사용한 번들의 원문 해시여야 한다. 나중에 다른 번들의 해시로 바꾸면 검증 대상이 달라진다.
- `verified_cases`는 실제 비교가 끝난 사례 수다. 누락·지원 불가·보류·실패를 성공 사례에 합치지 않는다. 합성 사례와 실제 관측 사례 수도 분리한다.
- 동일 입력 벡터의 Python/Java 예측 일치는 원관측에서 벡터를 만드는 과정의 검증이 아니다. 연구용 전일 자료 재현은 온라인 시점 입력 검증을 대신하지 않는다.
- `LesSourceFeatureCheck`의 `research_parity_passed=true`와 정상 종료는 연구 입력의 대조 결과다. 이 검사기는 `causal_differences`를 따로 기록한다. 연구 대조가 통과했다는 이유로 `feature_parity` receipt를 PASS로 만들지 않는다. 인과적 입력 차이가 남으면 원인과 온라인 계약을 확정하고 다시 대조해야 한다.
- 과거 평가 날짜 이후 자료로 고정 통계를 만들었다면 해당 날짜의 재생 점수를 당시 운영 성능으로 주장하지 않는다. `runtime.data_through` 이후 관측에서 평가하거나, 시점에 맞춰 다시 학습한 별도 번들로 검증한다. 별도 번들의 검증을 현재 번들의 receipt로 재사용할 수 없다.
- 실패/미실행 보고서를 PASS로 바꾸거나, 테스트용 receipt를 운영 폴더로 복사하거나, 검증을 실행하려고 임시 serving_ready=true를 만들어 실제 Worker에 활성화하는 절차는 금지한다. 독립 재생 환경과 오프라인 로딩 경계에서 먼저 검증해야 한다.

## 네 가지 검증의 필요한 입력과 출력

| 검사 | 실제 입력 | 보존할 출력과 판정 근거 |
|---|---|---|
| `feature_parity` | 해시로 고정한 원관측·배치·노선 참조·휴일·통계, 검증된 history 원점 또는 seed, 사례별 관측 ID/목표 정류장/기준 시각, 동일 시각까지만 사용하는 Python 기준 구현과 Java 온라인 입력 생성기 | 사례별 88/80개 입력과 결측 위치, 입력별 최대 차이/불일치 수, skip 이유. 연구 전체 자료와 causal 입력을 각각 비교. 새 배치, 재시작, 이전 pending, 같은 시각의 뒤 배치, 미래 입력 배제, seed 압축 전후를 확인. 비교 허용오차와 제외 조건은 실행 전에 고정한다. |
| `historical_replay` | 같은 번들과 실제 수집 순서의 관측, 통계 cutoff 이후 평가 기간, 격리 DB, 동일 품질·타깃·scope 정책, 온라인 시점까지만 허용하는 재생 실행기 | 관측/목표/모델별 발행 수·보류 이유·입력 snapshot·예측값, 미래 자료 사용 여부, 중복 처리/재시작 결과. 좌석은 하차 뒤 추정값, 확률은 출발 만석으로 따로 비교하며 기대값 허용오차는 1e-9. 실제 발행 경로 전체가 실행됐다는 근거 필요. 현재 합성 lifecycle 테스트만으로 이 실제 관측 검증을 대체하지 않는다. |
| `settlement` | 실제 편도 연결 자료, 목표별 하차 파라미터와 bundle hash, 품질 판본, 구간 확정 시각, 별도 기준 라벨 계산 결과 | 하차 뒤 좌석 추정값/출발 만석 각각의 라벨·근거 관측·known_at·상태/이유 대조. 입력 부족과 LOST 분리, 기존 정수 성적 혼입 없음, 품질 재평가와 보관/삭제/복원 후 중복 없음. 목표별 MAE 및 확률 지표와 미정산 건수. |
| `runtime_validation` | 실제 배포와 같은 설정의 격리 Worker/API, 두 모델의 불변 번들, 실제 규모 입력, 실패/복구 시나리오 | 활성화 거부/전환/기존 모델 복귀 결과, API 좌석/확률 목표와 원확률 보존, 타깃 정산 분리, 지원 범위 외 거부, snapshot 크기·DB 조회량·tick 지연·메모리. 운영 한도 기준과 측정 결과를 같이 보존한다. 로컬 CI 통과는 실제 운영 부하 통과와 구분한다. |

현재 구현에는 연구 입력 대조 실행기, 단위 테스트, 실제 PostgreSQL lifecycle 테스트가 있다. 실제 운영 범위의 시간순 Worker 재생 실행기와 네 보고서의 판정을 계산하는 receipt 생성기는 아직 제공하지 않는다. 그러므로 현재 코드로 가능한 부분 검증을 모두 수행하더라도, 위 입력·출력이 갖춰지기 전까지 운영 receipt 네 개를 수동으로 채워 활성화해서는 안 된다.

## receipt와 포장 계약

검증이 실제로 완료되면 검사별로 `verification-<check>.json`과 `evidence-<check>.json`을 보존한다. receipt의 필수 값은 다음과 같다. 아래는 필드 설명이며 PASS 예시 파일이 아니다.

- schema: `les-serving-verification-v1`
- check: 위 네 검사 중 하나
- status: 실제 판정 결과. 포장 도구는 네 검사 모두 PASS일 때만 허용
- source_manifest_sha256: 비교에 사용한 오프라인 manifest 원문 SHA-256
- feature_contract_version: `les-online-v1`
- verified_cases: 실제 검증 완료한 양의 정수 사례 수
- evidence_file / evidence_sha256: 해당 원본 보고서 JSON 파일 이름과 바이트 SHA-256

보고서에는 실행 명령, 코드 판본, 실행 시각, 자료 해시/기간, route/target별 사례 수, 불일치/보류 수와 상세 위치, 허용오차/판정 기준, 측정값을 포함한다. 개인정보·접속 비밀은 넣지 않는다. 파일 해시는 증거의 동일성을 검사할 뿐 서명이나 독립된 실행 인증을 제공하지 않는다.

운영 검증이 완료된 뒤에만 다음 포장을 실행한다. 출력은 새 불변 경로여야 하며 원본 false 번들은 바뀌지 않는다.

```sh
"$LES_PYTHON" backend/tools/les_model/prepare_serving_bundle.py \
  --source "$LES_RUN/java-inference-bundle-v2" \
  --receipts /private/path/verified-receipts \
  --release-id les-verified-release \
  --output /private/path/new-serving-release
```

이후 `maintenance-app bundle-check --directory /private/path/new-serving-release`는 파일 계약과 수치 기준 사례를 다시 확인한다. 이 명령도 모델을 활성화하지 않고 정확도나 실제 운영 부하를 새로 평가하지 않는다.
