# PROVISIONAL_19D 임시 교체 승인 기록

## 결정

- 결정 일시: 2026-09-03 01:15 KST
- 결정 주체: 사용자
- 대상 release: `v41b-8194bde56d86f365afd6`
- 대상 bundle digest: `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632`
- 결정: `PROVISIONAL_19D`의 release gate 실패를 인지한 상태에서, 2026-09-03 학원 이관 작업과 함께
  현재 temporary smoke 모델을 이 bundle로 교체한다.

이 결정은 required-date gate에 대한 명시적 임시 예외다. 모델을 canonical 또는 release-qualified로
재분류하지 않으며, 교체 실행은 승인된 이관/cutover 절차에서만 수행한다.

## 예외로 승인된 gate와 근거

| 항목 | canonical 요구 | 실제 | 판정 |
|---|---:|---:|---|
| 완료된 KST 날짜 | `N>=30` | `N=19` | `FAIL_N_LT_30`, 임시 교체 예외 승인 |
| split | development + calibration 7일 + holdout 7일 | development 9일 + calibration 5일 + holdout 5일 | provisional fallback |

사전 final-refit 후보의 combined holdout은 다음과 같다.

| rows | full rows | full prevalence | shifted Brier | shifted log loss | calibration-in-large | MAE | RMSE | CRPS |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5,719,182 | 117,208 | 2.049384% | 0.0097065373 | 0.0362140060 | -0.0005390037 | 3.152506 | 4.941879 | 2.203714 |

PMF maximum sum error는 `6.66e-16`, maximum `p0` error는 `4.44e-16`이다. 이 수치는 5일 holdout의
기술 통계이며 canonical 성능 주장으로 승격되지 않는다. route×horizon, time-slot, weekday/weekend,
날짜별 상세는 기존 `VALIDATION.md`에 고정돼 있다.

## canonical 재학습 권고

동일 계약 아래 새로 닫힌 사용 가능 날짜 11개 이상을 확보해 `N>=30`이 되는 즉시 다시 학습한다.
현재 날짜 연속성이 유지된다는 전제에서 가장 이른 calendar checkpoint는 2026-09-12 KST partition이
2026-09-13 00:15 KST 이후 닫히는 시점이다. 다만 개인 S3 수집은 종료됐고 academy EC2/RDS가 sole source이므로,
9/2~9/12 각 partition의 통합 source authority·누락·quarantine·24-block gate를 검증한 뒤에만 이 시점을
canonical 재학습 시작점으로 사용한다. 한 날짜라도 완전성 또는 block gate를 충족하지 못하면 그만큼 연기한다.

재학습 결과는 canonical 7일 calibration + 7일 holdout split, exact d856-or-later Java 검증, route별 seed
재생과 atomic cutover를 다시 통과해야 한다. 이 임시 승인은 그 재학습 의무를 없애지 않는다.

## 이 승인이 바꾸지 않는 것

- `bundle/manifest.json`: SHA-256
  `8a39fbf8a828e8e490d500d9b99b6235c8fe7cff896f1986e9f186ddee3c33e4` 그대로
- `bundle/weights.safetensors`: SHA-256
  `5b906da96d7b3e4b45c5e9d970df41c499f0d457756b853b613f672f589a3228` 그대로
- bundle identity: `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632` 그대로
- aggregate seed: SHA-256
  `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4` 그대로
- 기존 handoff root manifest: SHA-256
  `492933b1493d6da296b9ff087e9efa7d7ce676afa8450cd3a2e471a2f981278d` 그대로
- 기존 전달 ZIP: SHA-256
  `0d578f80eaebbeab6eddb545c9554773e2a270d6518df8f1a7f333da0f372a06` 그대로

`APPROVAL.md`는 위 root manifest와 ZIP 생성 뒤 추가된 sidecar이며 둘의 hash 대상에 포함되지 않는다.
기존 `README.md`와 `VALIDATION.md`는 root manifest에 고정된 파일이므로 이 승인 기록을 넣기 위해 수정하지 않았다.

## 여전히 필요한 통제

- aggregate seed merge/import, temporary generation cleanup, 공식 첫 generation, model deployment 활성화는
  `seed/ATOMIC_CUTOVER.md`와 승인된 combined cutover runbook 순서를 따른다.
- `MODEL_BUNDLE_PROMOTE_ON_START=false` 상태에서 파일 교체만으로는 승격되지 않는다.
- 이 승인 기록은 현재 data-analysis 워커에게 commit, push, 원격 전송, DB write 또는 배포 실행 권한을 주지 않는다.
- manifest `sourceCommit` 의미와 runtime seed import 경로의 기존 `DIVERGES` 판정은 그대로다.
