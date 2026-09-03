# 검증 결과

## 최종 분류

- 운영 Python contract: **PASS**
- 실제 EC2 활성 bundle과의 identity: **PASS**
- 현재 dev v4-1 Java loader에 drop-in: **FAIL / NON_COMPLIANT_WITH_V4_1**
- dev API가 요구하는 확률 방향·범위·PMF 말단: **PASS**
- “dev runtime이 dummy bundle로 예보 중”이라는 전제: **FALSE** — dummy는 test fixture뿐이다.

## 1. 파일·신원 검증

| 검사 | 기대 | 측정 | 결과 |
|---|---|---|---|
| `manifest.json` 크기 | artifact file | 61,681B | PASS |
| manifest SHA-256 | `2dce5e…40405` | 동일 | PASS |
| `weights.safetensors` 크기 | artifact file | 235,752B | PASS |
| weights SHA-256 | `680ef1…be4` | 동일 | PASS |
| bundle digest | SHA256(manifest bytes + weights bytes) = `652ee3…f9335` | 동일 | PASS |
| release ID | `a18-a748cba6ca735e36` | 동일 | PASS |
| data through | `2026-08-23T14:59:56Z` | 동일 | PASS |
| manifest canonical JSON | UTF-8, key sort, compact, final LF | byte exact | PASS |
| manifest 내 실제 HMAC 값 | 없어야 함 | 0 | PASS |

상세 기계 판정은 `processed/bundle-validation.json`에 있다.

## 2. tensor 검증과 값 요약

모든 float tensor는 finite이고 모든 `*_seen`/`*_fitted`는 0·1뿐이다.

| tensor | shape | 값 범위/flag 수 |
|---|---:|---|
| `hurdle_coefficients` | 2×12×31 | -8.502404 ~ 2.901986, 0값 55 |
| `anchor_coefficients` | 2×12×2 | 0.009913 ~ 0.989204, 0값 0 |
| `sign_coefficients` | 2×12×2×31 | -9.795207 ~ 11.838952, 0값 110 |
| `bin_coefficients` | 2×12×2×9×31 | -15.179228 ~ 11.444352, 0값 2,319 |
| `bin_fitted` | 2×12×2×9 | fitted 386, unfitted 46 |
| `lead_seconds` | 2×12 | 90.104 ~ 1,740.189초, 모두 양수 |
| `band_seen` | 2×12×3 | 72개 모두 1 |
| `cell_values` | 2×12×3×89×2 | -6.795680 ~ 3.563173, 0값 2,808 |
| `cell_seen` | 2×12×3×89 | seen 5,004, missing 1,404 |

전체 absolute coefficient 최댓값은 15.179228455946038이며 manifest의 기존 독립 검증 기록과
일치한다.

## 3. 실제 production runtime 계산 검증

고정된 두 노선 × 지평 1·4·8·12 = 8 context를 producer commit `c5ff99d`의 실제 loader와 predictor로
계산했다.

- loader의 canonical manifest, protocol digest, route reference, tensor shape/dtype 검증: PASS
- PMF cell 수: 71/71
- 모든 PMF 값 finite: PASS
- 모든 PMF 값 0~1: PASS
- 각 행 PMF 합 `|sum-1| <= 1e-12`: 8/8 PASS
- `PMF[0] == p_full` 오차 `<=1e-12`: 8/8 PASS
- `expectedSeats` 0~70: 8/8 PASS

기계 결과: `processed/runtime-validation.json`.

## 4. dev 소비 계약 검증

실제 파일을 dev-ready라고 부를 수 없는 이유는 단일 오류가 아니라 네 층이다.

1. **manifest 구조** — dev는 camelCase 20필드와 golden vector를 요구한다. actual은 snake_case
   production manifest이며 embedded protocol/training/weights를 가진다.
2. **feature 이름과 의미** — 31개 수는 같지만 이름·시간대·혼잡도·streak·앞차 분모·route·위치·spline·cell이 다르다.
3. **tensor 집합** — dev는 5개만 알고 unknown을 거절한다. actual은 feature를 재현하는 동결 tensor 4개를 더 가진다.
4. **신원 규칙** — dev `identityDigest`/golden contract와 Python release/bundle digest contract가 다르다.

따라서 Java loader를 실제 파일로 직접 실행하기 전에 manifest unknown-field 검사에서 거절될 것이며,
필드명을 고쳐도 feature names, tensor extras, golden vector에서 계속 거절된다. 검증을 약화해 통과시켜도
다른 좌표계 계수를 적용하므로 숫자는 의미가 없다.

## 5. dev dummy와 실제 bundle 비교

| 항목 | dev `DummyBundle` | 실제 운영 bundle | 판정 |
|---|---|---|---|
| 용도 | loader/activation 실패 경로 test fixture | 실제 EC2 serving | 다름 |
| runtime 기본 사용 | 사용 안 함 | EC2에서 active | 다름 |
| coefficient 생성 | Java `Random(20260901)`, Gaussian 표준편차 약 0.4의 임의 수 | 10일 실제 S3 데이터 적합 | 다름 |
| flag 생성 | 90% 확률로 1 | 실제 event 기준 fitted 386/432 | 다름 |
| feature names | dev Java 31열 | production Python 31열 | DIVERGES |
| tensor | dev 5개 | 실제 9개 | DIVERGES |
| golden vector | 같은 Java predictor가 자기 값으로 생성; 구현 독립성 없음 | actual manifest에는 dev golden 없음 | DIVERGES |
| data through | 하드코딩 `2026-08-30T14:59:56Z` | 실제 `2026-08-23T14:59:56Z` | dummy 값은 provenance 아님 |
| feature contract | `seat-feature-contract-v4-1-draft` | embedded `evaluation-v6` protocol | DIVERGES |
| 성능/실데이터 주장 | 불가 | production contract에 `performance_claims=false`; 별도 평가만 존재 | dummy는 비교 기준 아님 |

## 6. 원천 inventory와 privacy 검증

- 2026-09-02까지 collector S3가 전진함을 `processed/source-inventory.json`으로 확인했다.
- record 145,660개/1,776,593,699B, raw 145,535개/487,419,569B.
- schema-only sample에서 vehicle/plate/HMAC 필드 경로를 확인했지만 값·object key는 출력하지 않았다.
- 패키지에 row-level source object 0개, 원본 ID 0개, HMAC 값 0개, plate 값 0개다.
- `raw/aggregate-build-receipt.json`은 저장소가 검증한 aggregate-only receipt이며 privacy flags가 모두 false다.

## 7. end-to-end 재계산

물리 경로 정규화 뒤 전체 재계산은 성공했다.

- source closure: record 65,152 / raw 65,095 / point 114,945 / finalized 1,236,608 — PASS
- source manifest SHA-256: `3e1628…15536` — PASS
- release ID: `a18-a748cba6ca735e36` — PASS
- rebuilt bundle digest: `652ee3…f9335` — PASS
- rebuilt manifest vs deployed manifest: byte-identical — PASS
- rebuilt weights vs deployed weights: byte-identical — PASS
- 실행 시간 869.222초, peak RSS 1,512.859MiB

첫 두 진단 실행은 macOS의 `/var` symlink가 producer의 path digest `relative_to()`와 충돌해
`unexpected_ValueError`로 fail-closed됐다. Python 3.11 여부와 무관했고 데이터 검증 실패가 아니었다.
임시 작업 경로를 `pwd -P`로 정규화한 뒤 upstream source는 수정하지 않고 재실행했다.
적합 중 NumPy matmul overflow/invalid 경고가 발생했지만 기존 검증 기록의 두 독립 빌드에서도 같은
경고가 있었다. 최종 9개 tensor finite 검사, production loader, inference smoke와 byte digest가 모두
통과했으므로 경고를 숨기지 않고 PASS와 함께 기록한다.

## 8. 현재 평가 파이프라인 이상

이는 bundle 파일 검증과 별개지만 최신성 해석에 필요하다.

- latest evaluation data through: 2026-08-26T14:59:56.447Z.
- 8/30~9/1 daily 로그의 고정 실패: `ValidationError:date_partition_mismatch`.
- fallback 고정 실패: `RuntimeError:horizon_refresh_requires_exact_completed_corpus`.
- collector와 EC2 serving은 9/2에도 전진하지만 evaluator pointer는 멈췄다.
- production bundle은 원래 evaluator와 자동 연동되지 않으므로 여전히 8/23 학습판이다.

## 9. 실행한 검증 명령

```bash
scripts/validate-bundle.py bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36

scripts/validate-production-runtime.sh \
  /path/to/salmonbus-analysis \
  bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36
```

검증을 통과시키기 위한 하드코딩·허용 범위 확대·test 수정은 하지 않았다.
