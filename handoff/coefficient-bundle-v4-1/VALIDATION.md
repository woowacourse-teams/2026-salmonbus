# 검증 결과 — PROVISIONAL_19D

## 최종 판정

- dev d856 v4-1 bundle 파일 계약: **PASS**
- exact Java loader, golden, identity, feature parity: **PASS**
- Python tensor/runtime/PMF 검증: **PASS**
- route별 aggregate seed 검증: **PASS**
- temporary smoke exclusion negative test: **PASS**
- durable artifact privacy scan: **PASS**
- release qualification: **FAIL_N_LT_30**
- 배포·전송·RDS 적용: **수행하지 않음**

이 문서의 holdout 수치는 19일 provisional 9+5+5 split의 사전 final-refit 후보에 대한 기술 통계다.
정식 성능 주장이나 배포 승인으로 해석하면 안 된다.

## 1. 번들 identity와 canonical bytes

| 항목 | 실측값 | 결과 |
|---|---|---|
| target dev commit | `d856d10819bf1d018ad43fa63714cc348f1fc643` | PASS |
| classification | `V4_1_LOADER_COMPATIBLE_BACKFILLED_PROVISIONAL_19D` | 제한 명시 |
| release ID | `v41b-8194bde56d86f365afd6` | PASS |
| bundle digest / identity | `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632` | PASS |
| manifest | 2,247B, SHA-256 `8a39fbf8a828e8e490d500d9b99b6235c8fe7cff896f1986e9f186ddee3c33e4` | PASS |
| weights | 126,232B, SHA-256 `5b906da96d7b3e4b45c5e9d970df41c499f0d457756b853b613f672f589a3228` | PASS |
| golden vector digest | `b98eafcef2803d8792cc1005fef642f78d8e9e7a61cf046ae06b9f2fb4e0ecda` | PASS |
| feature contract | `observed-max-capacity-v1` | PASS |
| manifest envelope | 정확히 19개 known camelCase fields, key-sorted UTF-8, final LF 없음 | PASS |
| data through | `2026-09-01T15:22:36.572000Z` | 기록 |

d856 Java probe가 독립적으로 계산한 golden 값은 full chance `0.8128253142730959`, expected seats
`6.1348094202549195`다. strict Java verify와 Python scorer가 허용 오차 안에서 같은 값을 재현했다.

장기 프로세스는 d856 전환 직전 로드된 역사 상수 ed2cf742를 메모리에 갖고 시작했지만, 두 commit 사이의
feature/label/cell/loader/trajectory/migration 변경은 0이다. weights SHA는 그대로 둔 채 provisional manifest의
`sourceCommit`을 d856으로 고정한 다음에만 Java golden·identity를 생성했다. 이 경계는
`processed/dev-authority-update.json`과 `processed/dev-authority-finalization.json`에 기록돼 있다.

## 2. source closure와 freeze SHA 대응

| 범위 | batches / objects | observations | SHA-256 | 용도 |
|---|---:|---:|---|---|
| coefficient base, 8/14~9/1 | 142,129 accepted batches | 2,324,399 | `3cbdc35f…eb0f0` | fit·calibration·holdout |
| route별 catch-up | 7,064 accepted batches | 136,909 | route 1650 `32147c67…ac3`, 3330 `288c94b9…c7c5b` | seed·migration authority, coefficient 행 0 |
| source authority 합계 | 149,193 accepted batches | 2,461,308 | composite `75fc9d3f…05bc7` | bundle/seed handoff 정본 |
| post-boundary overlap | 811 batches | 14,924 | 별도 route receipt | continuity/dedupe evidence만, fit·seed·migration insert 제외 |

`f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e`는 감사의 2026-09-02
active-partition inventory SHA(15,750 record/raw objects, 140,161,072B)와 같다. 반면
`75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7`는 base accepted digest와
route별 catch-up authority를 합성한 semantic source-closure SHA다. 범위가 달라 서로 대체하지 않는다.

참고로 감사의 immutable-base 전체 object inventory SHA는 `db473053…35b9`, terminal full record/raw history
SHA는 `ad7dca91…fad8`이다. 두 값도 accepted-set/composite SHA와 범위가 다르다.

post-disable inventory 두 번은 95초 간격으로 동일했고 object/byte delta, late accepted catch-up object,
ambiguous boundary record가 모두 0이었다. base quarantine은 27 records/545 observation rows, route catch-up
quarantine은 1 record/24 observation rows다.

## 3. G/S 선택과 fitting

- grid: G=`0/10/30/60/90/120`, stress=`300/600`; S=`0/60/120/300`
- 선택: 모든 기존 24개 route×horizon block gate를 통과하는 가장 큰 non-stress 값 `G=60s`
- settlement: 배포 계약의 primary interval `S=60s`
- G=60 eligible rows: 19,189,112 / target 21,441,815
- 전체 target 기준 제외: 2,252,703 rows, 10.506121%
- baseline SETTLED 기준 guard 제외: 951,914 / 20,141,026 rows, 4.726244%
- G=60 state-changed rows 364,712; arrival-event-changed rows 675,544
- G=0/10/30/60은 gate PASS, G=90/120/300/600은 24개 block 모두 gate FAIL
- 모든 G=60 block은 19개 완료일, 200개 이상 행, positive-seat arrival을 보유
- chronological refit 240회; ridge/IRLS/anchor/direction/bin 상수는 `training-receipt.json`의 고정 계약과 일치
- Java에 없는 Platt/isotonic/temperature 보정 없음, cell fixed-point iteration 0회

날짜 split은 development 9일(8/14~8/22), calibration 5일(8/23~8/27), holdout 5일
(8/28~9/1)이다. 이는 `N=19` fallback이므로 canonical release split이 아니다.

## 4. combined holdout

| rows | full rows | prevalence | shifted Brier | shifted log loss | calibration-in-large | MAE | RMSE | CRPS |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5,719,182 | 117,208 | 2.049384% | 0.009707 | 0.036214 | -0.000539 | 3.1525 | 4.9419 | 2.2037 |

PMF maximum sum error는 `6.66e-16`, maximum `p0` error는 `4.44e-16`, 관측 최소/최대 확률은
`0.0`/`0.9999948939`다. 모든 대표 runtime context는 finite, [0,1], 71칸 합 1, `PMF[0]=p_full`,
expected seats 범위를 통과했다.

## 5. route × horizon holdout

| route | h | rows | full % | shifted Brier | shifted log loss | MAE | RMSE | CRPS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1650 | 1 | 142071 | 3.431 | 0.007637 | 0.027101 | 1.6323 | 2.5238 | 1.1690 |
| 1650 | 2 | 203050 | 2.806 | 0.008756 | 0.030222 | 1.8942 | 2.8552 | 1.3585 |
| 1650 | 3 | 228602 | 2.284 | 0.008140 | 0.028949 | 2.3498 | 3.5053 | 1.6788 |
| 1650 | 4 | 228948 | 2.071 | 0.008893 | 0.031563 | 2.7181 | 4.0265 | 1.9301 |
| 1650 | 5 | 231562 | 1.790 | 0.008304 | 0.030407 | 3.0200 | 4.4641 | 2.1344 |
| 1650 | 6 | 232180 | 1.455 | 0.007525 | 0.027380 | 3.2834 | 4.8587 | 2.3009 |
| 1650 | 7 | 239096 | 1.755 | 0.009545 | 0.034424 | 3.4958 | 5.2120 | 2.4381 |
| 1650 | 8 | 246743 | 1.687 | 0.010089 | 0.037877 | 3.7041 | 5.4841 | 2.5669 |
| 1650 | 9 | 242269 | 1.844 | 0.011341 | 0.041757 | 3.9195 | 5.8223 | 2.7101 |
| 1650 | 10 | 230104 | 1.610 | 0.010419 | 0.039876 | 4.0276 | 5.9549 | 2.7914 |
| 1650 | 11 | 244277 | 2.090 | 0.014184 | 0.053285 | 4.2951 | 6.3739 | 2.9602 |
| 1650 | 12 | 218710 | 1.999 | 0.014541 | 0.055856 | 4.3040 | 6.5035 | 2.9752 |
| 3330 | 1 | 147299 | 3.029 | 0.006415 | 0.021826 | 1.4063 | 2.4750 | 1.0177 |
| 3330 | 2 | 261236 | 3.052 | 0.009020 | 0.029946 | 1.7496 | 2.9657 | 1.2638 |
| 3330 | 3 | 262632 | 2.337 | 0.008805 | 0.031715 | 2.2836 | 3.6991 | 1.6406 |
| 3330 | 4 | 269983 | 2.030 | 0.008125 | 0.029757 | 2.5955 | 4.0683 | 1.8499 |
| 3330 | 5 | 265685 | 1.853 | 0.008567 | 0.031738 | 2.9293 | 4.5589 | 2.0651 |
| 3330 | 6 | 268885 | 2.032 | 0.009711 | 0.035769 | 3.1647 | 4.8788 | 2.2167 |
| 3330 | 7 | 270245 | 2.128 | 0.010742 | 0.039886 | 3.4059 | 5.2710 | 2.3729 |
| 3330 | 8 | 261627 | 1.795 | 0.009354 | 0.036406 | 3.4203 | 5.2948 | 2.3831 |
| 3330 | 9 | 263727 | 1.806 | 0.009973 | 0.039716 | 3.5385 | 5.4689 | 2.4560 |
| 3330 | 10 | 260449 | 1.784 | 0.010099 | 0.040964 | 3.6381 | 5.6171 | 2.5127 |
| 3330 | 11 | 251116 | 1.771 | 0.010286 | 0.041197 | 3.7121 | 5.7276 | 2.5542 |
| 3330 | 12 | 248686 | 1.766 | 0.010589 | 0.042384 | 3.8335 | 5.9027 | 2.6340 |

## 6. holdout stratification

| 축 | 값 | rows | full % | shifted Brier | shifted log loss | MAE | RMSE | CRPS |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| time slot | EVENING | 1249996 | 2.967 | 0.015178 | 0.051729 | 3.4567 | 5.3488 | 2.4423 |
| time slot | MORNING | 874108 | 6.260 | 0.021897 | 0.071765 | 3.6116 | 5.4376 | 2.5485 |
| time slot | OTHER | 3595078 | 0.706 | 0.004840 | 0.022176 | 2.9351 | 4.6600 | 2.0369 |
| day type | WEEKDAY | 4194188 | 2.660 | 0.012198 | 0.045243 | 3.2481 | 5.1579 | 2.2530 |
| day type | WEEKEND | 1524994 | 0.370 | 0.002854 | 0.011381 | 2.8897 | 4.2920 | 2.0681 |
| date | 2026-08-28 | 1402535 | 1.886 | 0.010187 | 0.035161 | 3.1386 | 4.9334 | 2.2048 |
| date | 2026-08-29 | 806920 | 0.622 | 0.004660 | 0.017778 | 3.0596 | 4.5927 | 2.2192 |
| date | 2026-08-30 | 718074 | 0.087 | 0.000825 | 0.004193 | 2.6988 | 3.9267 | 1.8982 |
| date | 2026-08-31 | 1396179 | 2.245 | 0.010781 | 0.038434 | 3.1417 | 4.9156 | 2.1431 |
| date | 2026-09-01 | 1395474 | 3.854 | 0.015638 | 0.062189 | 3.4644 | 5.5967 | 2.4115 |

표본 구성 차이가 크므로 층간 숫자를 단순한 모델 우열로 해석하지 않는다. 일자별 전체 분포와 raw/shifted
상세는 `processed/training-receipt.json`에 있다.

## 7. tensor 검증

| tensor | shape / dtype | min | max | nonzero | 결과 |
|---|---|---:|---:|---:|---|
| `hurdle_coefficients` | 2×12×31 / F64 | -12.933675 | 4.860636 | 504 | PASS |
| `anchor_coefficients` | 2×12×2 / F64 | 0.013812 | 0.984887 | 48 | PASS |
| `sign_coefficients` | 2×12×2×31 / F64 | -12.157271 | 25.335292 | 1,008 | PASS |
| `bin_coefficients` | 2×12×2×9×31 / F64 | -30.510384 | 36.120059 | 8,018 | PASS |
| `bin_fitted` | 2×12×2×9 / U8 | 0 | 1 | 387 | PASS |

모든 float는 finite이고 모든 fitted flag는 0/1이다. 상수-zero feature 10축과 unfitted bin 계수는 raw-bit
`+0.0` 검사를 통과했다.

## 8. exact Java 및 독립 검증

| 검증 | 로그/receipt | 결과 |
|---|---|---|
| Python replay/numerical contract 5 tests | `processed/python-contract-tests.log` | PASS |
| d856 Java probe + feature parity | `processed/java-probe.log` | PASS, 2 tests |
| finalized manifest strict verify + feature parity | `processed/java-bundle-verify.log` | PASS, 2 tests |
| loader-only rejection/acceptance path | `processed/java-loader-only.log` | PASS, 2 tests |
| d856 design-matrix/loader/residual/parity core | `processed/java-core-tests.log` | PASS, 96 tests |
| independent Python final bundle/runtime contexts | `processed/python-bundle-validation.json` | PASS |
| temp smoke exclusion negative fixture | `processed/temp-exclusion-negative-test.json` | PASS |

Java 실행 시간은 최초 probe 1m12s, strict verify 13s, loader-only 11s, core 11s였다. Java 검증은 모두
워크트리의 d856 object를 `git archive`한 격리 임시 디렉터리에서 실행됐다.

## 9. aggregate seed와 privacy

- seed: 723,046B gzip, 9,450,157B canonical JSON, 45,224 hourly rows
- seed SHA-256: `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4`
- canonical JSON SHA-256: `adcf0d04a4b67dc40bed6f3640f0f78697e9215bf794ff2b262f0ba0ec0a5ada`
- route 1650: 20,390 cells / 509,435 samples, authority `< 2026-09-02T12:49:33.041299Z`
- route 3330: 24,834 cells / 521,792 samples, authority `< 2026-09-02T10:27:52.390820Z`
- 마지막 6시간 generation: `2026-09-02T09:00:00Z`
- fill 평균 범위 0.0~1.0, net-boarding rate 범위 -0.692093~0.764463
- DB write 없음; formal cutover 전에 observation-only RDS delta merge가 필수

privacy scan은 bundle/processed/seed 45개 파일, 비압축 13,421,691B를 검사했다. raw object key, 원본 vehicle
필드값, HMAC 값, access key, private key, zero digest와 placeholder 발견은 모두 0이다.

## 10. 계산 자원과 남은 release gate

| 단계 | 시간 | peak RSS | 외부 쓰기 |
|---|---:|---:|---|
| full build | 6,805.483s | 2,871.641MiB | 없음 |
| route-specific seed refresh | 3,664.488s | 1,066.234MiB | 없음; S3 List/Get만 |

남은 세 갈림길은 `CONTRACT-COMPLIANCE.md`의 DIVERGES 그대로다.

1. 최소 11개 완료일을 더 확보해 `N>=30` canonical split으로 재빌드할지.
2. 사용자 승인 commit 뒤 exporter commit 의미로 `sourceCommit`을 완전히 해소하고 재빌드할지.
3. aggregate seed merge/import 경로와 temp cleanup/공식 첫 generation/activation을 별도 구현·승인할지.

