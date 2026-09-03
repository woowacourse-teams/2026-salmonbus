# PROVISIONAL_19D — dev v4-1 호환 실제 계수 번들

> **Release gate: FAIL (`N=19 < 30`).** 이 결과물은 실제 관측으로 적합했고 dev commit
> `d856d10819bf1d018ad43fa63714cc348f1fc643`의 exact Java loader·설계행렬·golden 계약을 통과했지만,
> 정식 release-qualified 모델이나 배포 승인본은 아니다.

dev drop-in 파일 계약은 **PASS**다. 다만 aggregate cell seed를 소비하는 backend merge/import 경로가 아직 없고,
운영 기본값 `MODEL_BUNDLE_PROMOTE_ON_START=false`이므로 파일을 복사하거나 worker를 재시작하는 것만으로는
활성 모델이 승격되지 않는다. EC2 전송, RDS 적용, deployment 활성화와 배포는 모두 별도 승인 범위다.

## 핵심 결과

- 분류: `V4_1_LOADER_COMPATIBLE_BACKFILLED_PROVISIONAL_19D`
- release ID: `v41b-8194bde56d86f365afd6`
- bundle digest / Java identity: `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632`
- manifest SHA-256: `8a39fbf8a828e8e490d500d9b99b6235c8fe7cff896f1986e9f186ddee3c33e4`
- weights SHA-256: `5b906da96d7b3e4b45c5e9d970df41c499f0d457756b853b613f672f589a3228`
- feature contract: `observed-max-capacity-v1`
- coefficient 기간: 2026-08-14~2026-09-01 KST, 완료일 19개, 142,129 batches / 2,324,399 observations
- source authority 전체: base + route catch-up = 149,193 batches / 2,461,308 observations
- backfill 정책: `G=60s`, `S=60s`, KST 6시간 chronological one-way generation
- aggregate seed: 45,224 hourly cells, SHA-256 `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4`
- 검증: d856 Java probe/strict/loader-only/feature parity/96 core tests, Python bundle/seed, temp exclusion, privacy 모두 PASS
- 외부 변경: AWS write 0, DB write 0, 전송 0, 배포 0

## 먼저 읽을 순서

1. `CONTRACT-COMPLIANCE.md` — dev v4-1 대비 MATCH/DIVERGES 판정
2. `VALIDATION.md` — identity, holdout, route×horizon, stratified 지표와 검증 로그
3. `CALCULATION.md` — label guard, 6시간 cell backfill, fitting 계산 의미
4. `PROVENANCE.md` — source freeze, route별 authority 경계와 재현 경로
5. `seed/ATOMIC_CUTOVER.md` — 승인 후 별도 구현이 필요한 seed/cutover 순서

## 산출물

```text
bundle/
├── manifest.json
└── weights.safetensors

seed/
├── cell-hourly-aggregate.json.gz
├── receipt.json
├── rds-observation-delta-contract.json
└── ATOMIC_CUTOVER.md

processed/
├── build-receipt.json
├── training-receipt.json
├── lag-sensitivity.json
├── fit-sensitivity.json
├── route-seed-refresh-receipt.json
├── java-finalization.json
├── java-*.log
├── python-bundle-validation.json
├── seed-validation.json
├── temp-exclusion-negative-test.json
└── privacy-scan.json
```

`raw/`에는 원천 행이 없다. 원본 vehicle ID는 실행 중 journey 연결을 위해 process-local 정수로만 치환되고,
HMAC은 무결성 검사에만 사용됐다. vehicle ID, HMAC, 번호판, raw body, object key와 서비스 비밀값은 durable
artifact에 남기지 않았다.

## 최종 파일의 로컬 재검증

아래 명령은 고정 d856 archive를 사용하며 원격 상태를 변경하지 않는다.

```bash
scripts/verify-with-dev-java.sh verify bundle
scripts/verify-with-dev-java.sh loader-only bundle
scripts/verify-with-dev-java.sh core-test

scripts/validate_bundle.py \
  --bundle bundle \
  --contexts processed/runtime-contexts.json \
  --output processed/python-bundle-validation.json

scripts/validate_seed.py \
  --seed seed/cell-hourly-aggregate.json.gz \
  --receipt seed/receipt.json \
  --output processed/seed-validation.json

scripts/validate_temp_exclusion.py \
  --root . \
  --output processed/temp-exclusion-negative-test.json

scripts/privacy_scan.py --root . --output processed/privacy-scan.json
```

`finalize-and-validate.sh`는 provisional manifest의 zero digest를 d856 Java 값으로 한 번 치환하는 전체 생성
파이프라인이다. 이미 final identity가 든 이 전달본에 다시 실행하는 명령이 아니다.

## 남은 release 차이

- 완료일이 19개뿐이라 `N>=30`과 canonical 7일 calibration + 7일 holdout gate를 충족하지 못한다.
- manifest `sourceCommit`은 승인된 exporter commit이 아니라 권위 있는 consumer commit d856이다. 미커밋 builder는
  SHA-256 receipt로만 고정돼 있다.
- privacy-safe aggregate seed를 runtime 통계로 merge/import하는 dev 경로가 아직 없다.
- temp smoke 세대 cleanup, seed merge, 공식 첫 generation, deployment 활성화는 원자 cutover와 별도 승인이 필요하다.

