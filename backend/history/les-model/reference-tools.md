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
- 생성 결과는 serving_ready=false다. 정적 통계 내보내기, Java 수치 대조, 실제 관측 입력, 정산과 API 연결을 마쳐야 운영 번들이 된다.
- 학습과 예측은 한 스레드로 제한한다. 메모리 상한을 보장하지 않으므로 실행 중 실제 점유를 확인한다.
