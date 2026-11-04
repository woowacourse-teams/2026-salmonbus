# SAL-359: 레스 모델 적용 기준 자료

현재 구현은 원본 계산을 이식하기 위한 첫 단계다. 서비스의 예보 계산이나 정산을 아직 교체하지 않았다.

`backend/tools/les_model/refit_pool.py`는 기존 실행에서 저장하지 않은 만석 확률 모델만 다시 학습한다. 학습 행 선택, 순서, 입력 정밀도, 파라미터를 유지하고 학습/평가 전체 예측을 이전 결과와 비교한 뒤 저장한다. 기존 좌석 모델 파일은 해시로 검사하고 변경하지 않는다.

`prepare_reference.py`는 각 노선의 평가 사례와 적은 좌석 사례를 선택하고 입력, 회귀 출력, 분류 확률, 최종 좌석, 만석 확률을 JSON으로 저장한다. 저장된 좌석 예측은 행 위치가 아니라 노선과 sample_row로 연결해 검사한다. null 입력은 결측값(NaN)이며 0으로 바꾸면 안 된다.

## 실행

Python 3.11.15와 tools 폴더의 requirements.txt에 고정한 버전을 사용한다. LES_PYTHON에는 해당 Python 실행 파일, LES_RUN에는 기존 재현 실행 폴더, LES_HANDOFF에는 원본 02-lab 폴더의 절대 경로를 지정한다. 원본 인계 코드와 로컬에서 수정한 연구 복사본을 혼동하지 않는다.

```sh
# 저장소 루트에서 실행. 고부하 명령은 하나씩 실행한다.
env OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 nice -n 10 "$LES_PYTHON" -m unittest discover -s backend/tools/les_model/tests -v

env OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 nice -n 10 "$LES_PYTHON" backend/tools/les_model/refit_pool.py \
  --run "$LES_RUN" --handoff-lab "$LES_HANDOFF" --output "$LES_RUN/pool-serving-reference"

env OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 nice -n 10 "$LES_PYTHON" backend/tools/les_model/prepare_reference.py \
  --run "$LES_RUN" --handoff-lab "$LES_HANDOFF" \
  --pool-reference "$LES_RUN/pool-serving-reference" --output "$LES_RUN/java-parity-reference"
```

출력 폴더가 이미 있으면 덮어쓰지 않고 중단한다. 비교 실패 시 모델을 공개 출력 경로에 저장하지 않는다. 테스트의 작은 입력은 합성 사례다. 전체 데이터 비교 결과는 실제 실행 receipt로 확인한다.

## 필요한 파일

- 원본 handoff의 scripts/scoreboard.py, scripts/gate_q35.py, recover/analysis/samples3.py, recover/analysis/exp2.py.
- 실행 폴더의 environment.json.
- lab/data/derived/bench_frame.pkl, bench_X_time.npy, candidate_gate_base.pkl.
- lab/models/reproduced/gb_after_entry.joblib, gate_after.joblib, gate_constants.json.

pickle/joblib은 코드를 실행할 수 있는 형식이다. 이 명령은 우리가 로컬에서 생성한 신뢰할 수 있는 산출물만 읽는 용도다. 외부에서 받은 임의 파일을 넣지 않는다. 원자료와 생성 결과는 Git에 넣지 않는다. 기준 모델의 해시는 contracts.py에 고정되어 있으므로 다른 재학습을 채택하려면 기준 변경을 명시적으로 검토해야 한다.

## 결과의 의미

- 정확히 비교하는 대상은 이번 복원 자료로 학습한 실행이다. 레스 원본 추출본/학습 객체와의 완전한 동일성은 확인되지 않았다.
- 하차 후 좌석과 출발 만석 확률은 서로 다른 시점을 예측한다. 출발 좌석 여유를 개인 탑승 확률로 바꾸지 않는다.
- 원본의 평가 점수 계산은 확률을 0.000001~0.999999로 제한하지만 예보 출력 자체는 제한하지 않는다.
- 첫 단계 생성 결과는 serving_ready=false다. 아래 두 번째 구현에서 통계 내보내기와 Java 수치 대조를 수행했다. 실제 관측 입력, 정산과 API 연결은 남아 있다.
- 학습과 예측은 한 스레드로 제한한다. 메모리 상한을 보장하지 않으므로 실행 중 실제 점유를 확인한다.

## 통계 묶음과 Java 대조 (두 번째 구현)

`export_bundle.py`가 학습된 세 트리 모델, 입력 계약, 정적 통계, 하차 추정 파라미터, 두 노선 참조, 기준 사례를 묶는다. `export_statistics.py`는 해시로 고정한 원본 samples3.py의 통계 계산 구간과 queue_state.py 함수를 실행한다. 새 통계식을 쓰거나 하차 모델을 재학습하지 않는다.

- 정류장과 시간대별 도착률, 순승차, 만석 비율, 출발 좌석 평균과 기본값.
- 정류장 사이 이동시간 중앙값. 원본과 같이 전체 통과 사건에서 계산한다.
- 목표 정류장과 출퇴근 구간별 하차량, 하차 후 좌석, 도착 좌석 평균. 30% 학습 표본을 고르기 전의 전체 학습 기간으로 계산한다.
- 재현에 사용한 하차 추정 JSON 원문 값과 정류장 정보.
- 위치와 방향 계산에 쓰는 기존 route-reference.json 및 경유 특징에 쓰는 routes.json의 정류장 정보. 두 참조를 합쳐 하나로 바꾸지 않는다.

추가 입력은 RUN/lab/data/rebuild의 batches, obs_*_trips, events, samples, routes.json, derived의 ylabel과 alight_fit, data/bundle_coldstart20/route-reference.json이다. 모두 신뢰할 수 있는 로컬 재현 산출물이어야 한다.

```sh
# 저장소 루트에서 기존 환경 변수 사용. 출력 경로는 새 폴더로 지정한다.
env OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 nice -n 10 "$LES_PYTHON" backend/tools/les_model/export_bundle.py \
  --run "$LES_RUN" --handoff-lab "$LES_HANDOFF" \
  --pool-reference "$LES_RUN/pool-serving-reference" \
  --reference "$LES_RUN/java-parity-reference" --output "$LES_RUN/java-inference-bundle-v2"

# backend 디렉터리. JDK 21을 JAVA_HOME으로 지정한다.
nice -n 10 ./gradlew :forecasting:test --tests '*les.*Test' --tests '*BundleLoaderTest' --tests '*SeatForecastResultTest' \
  --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1g -Xms128m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=1'
nice -n 10 ./gradlew :forecasting:verifyLesParity \
  -PlesBundle="$LES_RUN/java-inference-bundle-v2" -PlesParityOutput="$LES_RUN/java-parity-final.json" \
  --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1g -Xms128m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=1'
```

LesInferenceBundle은 파일 해시, 입력 순서, 출력 목표, 트리 클래스, 노선 통계 존재를 검사하는 별도 로더다. 기존 운영 활성화에 등록하지 않았다. NumericTreeEnsemble은 초기 점수부터 순서대로 잎 값을 합하고 결측 분기와 확률 변환을 적용한다. 잎에 이미 들어간 학습률을 다시 곱하지 않는다. 좌석 입력은 원본의 float32로 변환하고, 확률 입력은 float32 특징에 정수 거리를 붙여 만든 float64를 받는다.

### 2026-11-04 확인 결과

- 8개 노선 목표 평균값 1,864,673건이 학습 입력과 동일했다.
- 나머지 통계를 사용하는 입력 65,536건에서 최대 차이는 2.220446049250313e-16이었다.
- 실제 사례 1,023건과 합성 경계/결측 사례 489건의 Python/Java 대조가 통과했다. 최대 좌석 차이 8.881784197001252e-16석, 만석 확률 차이 2.220446049250313e-16. 기준은 1e-9이다.
- Python 단위 테스트 12개와 Java 관련 테스트 69개가 통과했다. Java 전체 테스트 수 기준은 신규 13개를 반영했다. 전체 backend build/CI를 실행한 결과는 아니다.
- 원본 확률 모델의 클래스가 0.0/1.0인 경우도 정수 의미 0/1로 읽는다. 0.5 같은 다른 클래스나 순서 변경은 거부한다.
- 묶음은 17개 구성 파일과 manifest, 약 15.17MiB다. 파일별 해시와 통계 검증 기록을 포함하고 Java 결과에는 묶음 manifest 해시를 남긴다.

### 검증 범위

이번 일치는 재학습한 로컬 모델에 같은 입력 벡터를 준 결과다. Java에서 원관측으로 88/80개 입력을 만드는 과정, 실제 정산, API와 운영 지연은 아직 검증하지 않았다. 전체 115만 평가 행의 Java 예측을 모두 비교한 것은 아니다. 기준 사례는 노선별로 선택한 실제 행이며 경계 사례는 별도 합성 입력이다.

원본 통계에 평가 날짜 이후 자료가 포함되는 조건도 보존했다. 이 묶음과 연구 점수를 과거의 실제 운영 성능으로 해석하면 안 된다. 미래 정보 없이 관측을 시간순으로 재생하는 검증이 남아 있다. serving_ready=false이며 기존 Worker에 활성화할 수 없다.
