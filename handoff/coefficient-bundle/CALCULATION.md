# 실제 계수 계산과 소비 경로

## 결론부터

실제 운영 계수는 Lambda 안에서 계산되지 않는다. `salmonbus-collector` Lambda가 GBIS 응답을
S3 `records/`·`raw/`에 쌓고, `salmonbus-analysis`의 private in-process adapter가 승인된 날짜
창을 S3에서 읽어 24개 `(노선, 지평)` 모델을 적합한다. 완성된 번들은 릴리스 artifact에 묶여
EC2 섀도 백엔드가 읽는다. `salmonbus-model-evaluator` Lambda는 일일 채점기이며 최종 번들
adapter를 포함하지 않는다.

```text
EventBridge Scheduler (매분, KST)
  -> salmonbus-collector Lambda
  -> private S3 records/ + raw/
  -> offline final_bundle_adapter (사람이 승인한 동결 창)
  -> manifest.json + weights.safetensors
  -> immutable release artifact
  -> EC2 salmonbus-demo-api / shadow runtime
  -> serving S3 live/materialized + live/control pointer

별도 경계:
2026-salmonbus dev worker-app의 Java loader/feature contract
  -> 현재 운영 Python 번들과 직접 연결되어 있지 않으며 형식·특징 의미가 다름
```

## 1. 수집과 동결

- 실행 형태: `salmonbus-collector` Lambda. EventBridge Scheduler
  `salmonbus-adaptive-heartbeat`가 `cron(* * * * ? *)`, `Asia/Seoul`, flexible window OFF로 호출한다.
- 최신 record에 적힌 전략: `adaptive-kst-v1.2.0`; day 구간은 1분 invocation 안에서 3회전한다.
- 노선: 1650(`234000050`), 3330(`204000057`).
- 저장: 호출 하나·노선 하나당 private raw 응답과 검증 record를 각각 S3에 쓴다.
- record에는 응답 body SHA-256, HTTP/timing, 수집 전략, 정규화된 bus 행, raw object 참조가 있다.
  원본 `vehId`·`plateNo`와 HMAC도 private record에 있으므로 이 패키지에는 행 본문을 넣지 않았다.
- 최종 적합 창은 2026-08-14~2026-08-23 KST 열흘이다. 각 날짜는 다음 날 00:15 KST에
  object inventory를 동결하고 `key<TAB>etag<TAB>size<LF>`의 bytewise key 정렬 SHA-256으로 묶는다.
- 동결 closure: record 65,152개, raw 65,095개, 정규 관측 992,866행, passage point
  114,945건, 최종 적합 예 1,236,608행. raw가 없는 실패 record 57개와 station mismatch
  record/observation 각 4개가 장부에 남는다.

근거는 producer commit `c5ff99d`의 다음 파일이다.

- `server/evaluator/current_public/evaluation/final_bundle_adapter.py:224-408` — 날짜·노선·동결·건수 계약
- 같은 파일 `:442-497` — 일별/전체 object inventory digest 검증
- 같은 파일 `:654-709` — raw/record 검증, quarantine, passage 변환, 메모리 내 material
- 실제 번들 `manifest.json.training.provenance.document`

## 2. 라벨·연속성·필터

실제 번들에 내장된 `evaluation-v6` protocol이 계산 입력 계약이다.

- 목표: 대상 정류장을 출발한 뒤 잔여석이 0일 확률과 0~70석 PMF.
- 시각 권위: `timing.response_received_at`, KST.
- point label: 통과 순번이 정확히 1 증가하고 두 관측 간격이 90초 이하인 사건.
- 새 연속 구간: KST 날짜 변경, `last_passed` 후퇴, gap이 90초를 초과할 때.
  정확히 90초는 같은 구간이다.
- 제외: 잔여석 -1, KST 날짜를 넘는 라벨, 승차 불가/경유 정류장, 규격 밖 `stateCd`,
  station mismatch quarantine.
- 허용 `stateCd`: 0·1·2. `stateCd == 1`은 `last_passed = stationSeq - 1`, 나머지는
  `last_passed = stationSeq`다.
- 상류 관측: 대상에서 지평만큼 앞선 정류장을 출발한 첫 관측. 지평은 1~12.
- 차량 결합 키: `pseudonyms.vehId_hmac`. 메모리에서만 사용하고 bundle·receipt·stdout에는 쓰지 않는다.
- 정원: 이전에 완료된 KST 날짜의 최대 잔여석, 예측 시각 이하의 당일 running maximum,
  현재 상류 잔여석의 최대. 같은 날 미래 관측과 endpoint-only fallback은 금지한다.

## 3. 그룹 키와 집계 창

### 모델 적합 단위

계수를 공유하지 않는 `(route in [1650,3330], horizon in [1..12])` 24개 블록이다. 각 블록은
최소 200행과 완료된 KST 날짜 2개 이상이 필요하다. 실제 fit row는 1650의 39,719~46,690행,
3330의 54,095~68,239행이고 합계 1,236,608행이다.

### lead와 시간대

각 `(route,horizon)`의 실제 `arrived_at - prediction_at` 중앙값을 `lead_seconds`로 동결한다.
시간대는 관측 시각이 아니라 다음 추정 도착 시각으로 정한다.

```text
estimated_arrival = prediction_at + median_lead[route,horizon]
morning = 07:00 <= KST(estimated_arrival) < 09:00
evening = 17:00 <= KST(estimated_arrival) < 20:00
other = 나머지
```

실제 lead 범위는 90.104~1,740.189초다. 근거:
`server/model/features.py:21-74`.

### 셀 통계

각 route의 지평 1 material에서 `(station, time_band, KST day)`로 먼저 묶는다.

```text
day_fill = mean(1 - arrival_seats / capacity)
day_net  = mean(upstream_seats - arrival_seats) / mean(capacity)
cell raw = 날짜별 day 값의 단순 평균
cell z   = (cell raw - 같은 time_band 정류장 평균) / (모집단 표준편차 + 1e-9)
```

학습 행의 셀 특징은 자기 날짜를 뺀 leave-one-day-out profile을 쓴다. 대상 셀은 만석 양성 수에
따른 안정적 mask를 일부 적용해 결측 fallback도 학습한다. 서빙 profile은 전체 완료 날짜로 만든다.

- exact 셀이 없으면 정류장 순번 반경 4 안에서 `1 / distance²`로 z값을 평균한다.
- 이웃도 없으면 0과 missing=1.
- segment boarding은 `target-horizon+1 .. target`에서 실제 셀이 있는 z값 합을 `sqrt(seen)`으로 나눈다.
- 하나도 없으면 대상 주변 이웃 fallback.

근거: `server/model/features.py:89-217`, `server/model/training.py:178-240`.

## 4. 실제 31열 설계행렬

실제 운영 번들의 열 이름과 순서는 다음과 같다. 이 순서는 계수의 좌표계다.

| 열 | 실제 이름 | 계산 |
|---:|---|---|
| 1 | `intercept` | 1 |
| 2~3 | `time_morning`, `time_evening` | 추정 도착 시간대 one-hot |
| 4 | `time_unseen` | 해당 `(route,horizon)` 학습에 시간대가 없으면 1 |
| 5 | `upstream_ratio` | 현재 잔여석 / causal capacity |
| 6 | `upstream_zero` | 현재 잔여석이 0이면 1 |
| 7 | `upstream_capped_20` | `min(현재 잔여석,20)/20` |
| 8~11 | `crowded_0..3` | 원코드 0·1·2·3 one-hot; 4는 reference라 모두 0 |
| 12 | `capacity_ratio_68` | capacity / 68 |
| 13~14 | `slope`, `slope_missing` | causal 좌석 기울기 또는 0, 결측 지시자 |
| 15 | `full_streak_capped` | `min(streak,5)/5` |
| 16 | `previous_full` | 같은 대상 정류장의 직전 90분 passage가 만석이면 1 |
| 17~18 | `previous_seats_ratio_60`, `previous_seats_missing` | 직전 좌석 / 60 또는 0, 결측 지시자 |
| 19 | `route_3330` | 3330이면 1 |
| 20 | `station_ratio_60` | 대상 stationSeq / 60 |
| 21~28 | `spline_0..7` | `u=station/route_max`; `max(0,1-abs(u-k)*7)`, `k=0,1/7,...,1` |
| 29~31 | `cell_occupancy`, `segment_boarding`, `cell_missing` | 위 셀 profile |

`route_max_station`은 1650=88, 3330=84다. 식의 정본은
`server/model/features.py:220-262`, 순서는 `server/model/constants.py:25-60`이다.

## 5. 계수 적합

### 만석 허들

각 블록에서 31열 `X`와 `y = 1[arrival_seats == 0]`로 ridge logistic IRLS를 적합한다.

```text
p = sigmoid(clip(Xβ, -30, 30))
W = p(1-p) + 1e-9
H = X'WX + ridge*I
g = X'(y-p) - ridge*β
β <- β + solve(H,g)
```

ridge=1.0, 최대 60회, step max가 `1e-10`보다 작으면 멈춘다. 선형해가 singular면 least squares로
fallback한다. 근거: `server/model/distribution.py:15-43`.

### 중심점과 잔차

- anchor: `arrival/capacity ~ 1 + upstream/capacity`의 반복 가중 L1 근사, 40회.
- 중심 좌석: `rint(clip((a0 + a1*u/c)*c, 0, c))`, 0~70.
- 만석 도착은 잔차 적합에서 제외한다.
- sign 두 head: 잔차가 0인지, 잔차가 양수인지 각각 ridge logistic. 음수 방향은 남은 확률이고
  최소 `1e-6` 뒤 세 방향을 정규화한다.
- magnitude: 방향별 상대 경계 `(0,.03,.07,.12,.2,.32,.48,.7,1)`의 9구간. 한 구간의
  event가 10개보다 적으면 `bin_fitted=0`; 한 방향 event가 50개보다 적으면 그 방향 구간 전체가 미적합이다.
- 계수에 없는 미적합 구간은 추론에서 `1e-6`을 받고 다른 구간과 정규화한다. 방향 전체가
  미적합이면 그 방향 질량을 중심 잔차 0으로 돌린다.

근거: `server/model/distribution.py:46-171`.

### PMF 조립과 당일 보정

잔차 질량을 0~70석에 접고 0석 칸을 보정된 만석 확률로 고정한다. 1~70석은 남은
`1-p_full`에 맞춰 다시 정규화한다. `expectedSeats = Σ(seats * PMF[seats])`다.

당일 같은 `(route,horizon)`에서 예측 시각 이전에 도착이 확정된 행이 50개 이상이면
사전강도 `n0=200`, logit 이동 한도 ±3으로 만석 확률을 이동한다. 미래 결과나 다른 KST 날짜는
보지 않는다. 근거: `server/model/prior.py:58-97`, `server/model/distribution.py:174-250`.

## 6. 번들 생성과 판본

producer는 9개 tensor를 만든다.

| tensor | shape | dtype |
|---|---:|---|
| `hurdle_coefficients` | 2×12×31 | float64 |
| `anchor_coefficients` | 2×12×2 | float64 |
| `sign_coefficients` | 2×12×2×31 | float64 |
| `bin_coefficients` | 2×12×2×9×31 | float64 |
| `bin_fitted` | 2×12×2×9 | uint8 |
| `lead_seconds` | 2×12 | float64 |
| `band_seen` | 2×12×3 | uint8 |
| `cell_values` | 2×12×3×89×2 | float64 |
| `cell_seen` | 2×12×3×89 | uint8 |

`release_id`는 `data_through`, protocol digest, route-reference digest, training provenance digest를
canonical JSON으로 묶은 SHA-256 앞 16자리다. 이 release는 `a18-a748cba6ca735e36`, data through는
`2026-08-23T14:59:56Z`다. `weights.safetensors` SHA-256와 embedded protocol·route reference를
loader가 fail-closed로 검증한다.

## 7. dev Java 계산과의 핵심 차이

현재 dev는 같은 API 결과를 목표로 하지만 다른 좌표계다.

- 시간대: dev는 `observation_batch.response_received_at`; 운영 Python은 `prediction + frozen lead`.
- 혼잡도: dev는 정규화 등급 1~4; 운영은 원코드 0~3 one-hot, 4 reference.
- streak: dev 원 stop count; 운영 `min(streak,5)/5`.
- 앞차 좌석: dev `/ maximumSeats`; 운영 `/60`.
- route: dev 항상 0; 운영 3330 지시자.
- 위치: dev `station/largestStopOrder`, spline 8열은 0; 운영 `station/60`과 동결 spline 8열.
- `new_time_slot`: dev 0; 운영 `band_seen`으로 계산.
- 셀: dev DB 최신 as-of generation이며 지평 축 없음; 운영 번들에 route×horizon별 frozen z profile.
- dev loader는 5개 coefficient tensor만 허용하고 `lead_seconds`·`band_seen`·`cell_*`를 알 수 없는
  tensor로 거절한다.

따라서 공통 5개 tensor만 이름을 바꾸거나 manifest를 변환하는 것은 재현이 아니다. 실제 데이터로
dev 계약의 새 번들을 만들려면 dev 31열의 미정/0 열을 먼저 확정하고 그 좌표계로 재학습해야 한다.
