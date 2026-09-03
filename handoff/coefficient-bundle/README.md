# NON_COMPLIANT_WITH_V4_1 — 실제 운영 A18 계수 번들 인계

이 패키지는 2026-09-02 현재 EC2 섀도 데모가 실제로 쓰는 release
`a18-a748cba6ca735e36`을 S3 release artifact에서 읽기 전용으로 내려받아 검증한 것이다.

중요: 이 번들은 현재 dev Java v4-1 소비 계약에 맞지 않는다. 운영 Python contract에는 유효하지만
dev에 넣을 수 있는 최종 번들이 아니며, `MODEL_BUNDLE_DIRECTORY`가 이 디렉터리를 가리키게 하면 안 된다.

## 핵심 판정

- 수집기: **Lambda** (`salmonbus-collector`). EC2는 수집기가 아니라 collector S3를 읽는 shadow serving consumer다.
- 실제 수집: 2026-09-02까지 전진. inventory 시점 record 145,660개, raw 145,535개.
- 실제 계수 생산: Lambda가 아니라 `salmonbus-analysis` private offline adapter.
- 실제 번들 학습 창: 2026-08-14~08-23 KST, 10일, 최종 예 1,236,608행.
- 실제 번들: manifest 61,681B + weights 235,752B, bundle digest `652ee3…f9335`.
- production loader/tensor/PMF 검증: PASS.
- 동일 source closure 전체 재계산: PASS; manifest와 weights 모두 배포 파일과 byte-identical.
- dev Java drop-in 호환: FAIL — manifest, feature 좌표계, tensor 집합, 신원 규칙이 다름.
- dev dummy: runtime bundle이 아니라 seeded random test fixture.
- 최신성: collector·EC2는 9/2까지 움직이지만 bundle은 8/23 학습판이고 evaluator pointer는 8/26에서 멈춤.

## 먼저 읽을 순서

1. `CONTRACT-COMPLIANCE.md` — v4-1 우선 판정과 불일치 매트릭스
2. `CALCULATION.md` — S3에서 24개 계수 블록과 PMF가 만들어지는 실제 수식
3. `PROVENANCE.md` — AWS·Git·데이터 기간·SHA·읽기 전용 재현 명령
4. `VALIDATION.md` — 파일·tensor·runtime·dummy 비교와 실패/통과 결과

## 실제 bundle 위치

```text
bundle/
└── NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36/
    ├── NON_COMPLIANT_WITH_V4_1.md
    ├── manifest.json
    └── weights.safetensors
```

원래 파일명은 production loader 계약이므로 바꾸지 않고 상위 디렉터리와 marker 파일에
`NON_COMPLIANT_WITH_V4_1`을 표시했다.

## 빠른 검증

```bash
scripts/validate-bundle.py \
  bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36

scripts/validate-production-runtime.sh \
  /path/to/salmonbus-analysis \
  bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36
```

AWS에서 원본 artifact를 다시 받을 때:

```bash
export AWS_PROFILE=<READ_ONLY_PROFILE>
scripts/download-production-bundle.sh <NEW_OUTPUT_DIRECTORY>
```

실제 동결 데이터를 다시 계산할 때는 Python 3.11과 기존 AWS read profile이 필요하다.

```bash
export AWS_PROFILE=<READ_ONLY_PROFILE>
export AWS_REGION=ap-northeast-2
export A18_PYTHON_BIN=<PYTHON_3_11>

scripts/rebuild-production-bundle.sh \
  /path/to/salmonbus-analysis \
  <NEW_OUTPUT_DIRECTORY>
```

이 스크립트는 S3 List/Get만 수행하고 row-level intermediate를 쓰지 않는다.

## 디렉터리 설명

- `raw/`: 민감 row raw 대신 안전한 aggregate build receipt와 제외 정책
- `processed/source-inventory.json`: object key와 값을 내지 않은 수집량·기간·schema inventory
- `processed/infrastructure-observation.json`: environment 값과 원문 로그를 뺀 AWS 실행 상태
- `processed/dev-consumer-contract.json`: dev HEAD에서 추출한 소비 계약
- `processed/bundle-validation.json`: SHA·tensor·dev 비호환 기계 판정
- `processed/runtime-validation.json`: 8개 실제 predictor context의 PMF 검증
- `processed/rebuild-receipt.json`: 전체 in-process 재계산의 aggregate-only 영수증
- `processed/rebuild-verification.json`: 재계산 파일과 배포 파일의 byte 비교
- `bundle/`: byte-exact actual production coefficient bundle
- `scripts/`: read-only 다운로드·inventory·재계산·검증 도구

## 왜 raw 관측 본문이 없나

private collector record/raw에는 원본 `vehId`, `plateNo`, vehicle/plate HMAC이 있다. 작은 표본을 넣어도
10일 계수는 재현되지 않고 privacy 경계만 깨진다. 정식 adapter가 이 값을 메모리 안에서만 쓰도록
설계돼 있으므로, 이 handoff도 그 경계를 유지한다.

## dev-compatible 실제 번들을 만드는 다음 단계

현재 파일을 변환하는 일이 아니다.

1. dev에서 0으로 남은 `new_time_slot`·spline 8열과 정규화 상수/시간대/혼잡도/cell 축을 팀 계약으로 확정한다.
2. 그 feature contract로 실제 S3 동결 창을 다시 materialize하되 private join은 메모리에서만 처리한다.
3. dev가 요구하는 camelCase manifest, 5개 tensor 정책 또는 새 tensor schema, independent golden vector를 exporter가 만든다.
4. Java loader, golden parity, 71칸 PMF, API contract, 과거 holdout을 통과한 뒤에만 dev-ready라고 부른다.

이번 패키지는 이 결정들을 대신하지 않는다. 불일치 자체가 핵심 조사 결과다.
