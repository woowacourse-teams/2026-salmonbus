# S3 → RDS migration audit

이 디렉터리는 private `salmonbus-collector` history의 aggregate-only 감사 결과다. source object key,
실제 row payload, 원본 vehicle ID, plate 값, source HMAC 값, credential, 환경값은 포함하지 않는다.

target dev 계약 기준은 `d856d10819bf1d018ad43fa63714cc348f1fc643`다. audit worktree HEAD는
이전 `ed2cf742b0db368d7cf6eae2556b36bc156a5e72`이며, commit delta는 checkout 없이 git object로
검증했다. source 개인 계정은
read-only로만 확인했고 academy에는 접근하지 않았다. target 기준은 사용자가 EC2 내부 read-only
transaction으로 제공한 aggregate다. archive, SSH/rsync, RDS write, DELETE, 파일 삭제, commit, push,
PR, deploy는 수행하지 않았다.

향후 transfer archive는 `PRIVATE_SENSITIVE_NORMALIZED`이며 trajectory continuity를 위해 `vehicle_id`를
포함한다. 이 repository 산출물과 fixture에는 실제 vehicle 값이 없고, archive는 SSH·0600 file·0700
directory 경계에서만 다루며 삭제도 별도 승인 대상이다.

시작점은 [AUDIT.md](AUDIT.md), 기계 판정 정본은 [audit-summary.json](audit-summary.json)이다.
현재 산출물은 상위 24파일과 `scripts/` 10파일, 총 34파일이며 모두 미추적 상태다.

주요 파일:

- `inventory.json`: base 전체 일·노선·schema·field·identity·압축 집계
- `source-validation-frozen.json`: record/raw byte-level freeze 검증
- `continuity-window.json`: 두 current route_version의 route별 authority를 적용한 canonical catch-up/overlap 검증
- `continuity-window-final-1.json`, `continuity-window-final-2.json`: schedule disable 뒤 두 byte-audit receipt
- `source-freeze-confirmation-1.json`, `source-freeze-confirmation-2.json`: 두 List-only terminal freeze receipt
- `cutover-readiness.json`: quota incident, schedule disable, terminal freeze와 EC2-only cutover 판정
- `field-mapping.json`: S3 → RDS → model 소비처와 계정/암호화/transfer 조건
- `trainer-read-contract.json`: history/provenance/as-of 및 temp lineage 제외 계약
- `provenance.json`: source validator/실행 시점/toolchain 증거
- `target-dev-delta.json`: ed2cf→d856 네 파일의 mapping/trainer/import 영향 판정
- `postgres-sizing.json`: 합성 PostgreSQL 18 공간/시간 측정
- `archive-record.schema.json`, `archive-manifest.schema.json`: transfer 계약
- `temp-generation-manifest.schema.json`: temp-window cell generation 제외 계약
- `route-migration-receipt.schema.json`: 두 current version의 fail-closed valid_from update/rollback receipt 계약
- `route-mapping-summary.json`, `route-seed-1650.json`: validity-aware route mapping과 1650 reference evidence
- `acceptance-fixture.json`: synthetic acceptance fixture
- `scripts/audit_source_freeze_list.py`: object key를 내보내지 않는 List-only terminal receipt 도구
- `scripts/`: read-only audit, 검증, target dry-run, 합성 sizing 도구 10개
- `SHA256SUMS`: 자신을 제외한 33파일의 최종 digest

Terminal digest 범위는 서로 다르다. immutable base는 `db473053…35b9`, KST 2026-09-02 active
partition은 `f0decee3…a86e`, 둘을 합친 full record/raw history는 `ad7dca91…fad8`이다. 이관 manifest는
필요한 범위를 이름과 count/bytes와 함께 기록하며 digest만 단독으로 재사용하지 않는다.

검사:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 audit/s3-rds-migration/scripts/validate_artifacts.py
(cd audit/s3-rds-migration && shasum -a 256 -c SHA256SUMS)
```
