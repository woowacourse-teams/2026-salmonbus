# salmonbus-collector S3 → current RDS migration audit

target dev 계약 기준은 `d856d10819bf1d018ad43fa63714cc348f1fc643`이다. audit worktree HEAD는
이전 `ed2cf742b0db368d7cf6eae2556b36bc156a5e72`지만, commit delta를 checkout 없이 read-only로
검증해 아래 계약에 반영했다. 결론은 다음과 같다.

- 자동 이관 가능한 최대 검증 후보는 `observation_batch` 149,193행과
  `vehicle_observation` 2,461,308행이다. immutable base 142,129/2,324,399에 두 route별
  terminal catch-up 7,064/136,909를 더한 값이다. route roster 불일치 record 28개·그 record에
  속한 관측 569행은 fail-closed로 제외한다.
- target의 초기 기준점 303 batch/8,896 observation에 단순 가산한 하한은 149,496 / 2,470,204행이다.
  최신 route 상태는 route 2 / version 2 / stop 174이며 이관 전후 동일하다. 실제 실행 시에는 제공된
  초기 기준점 뒤에 계속 쌓인 target live observation 증분을 더해야 한다.
- record/raw byte·hash·envelope·행 대응 손상은 0건이다. semantic failure record는 964건이며
  손상과 구분해 batch 이력으로 보존한다.
- source `vehId`는 current private `vehicle_id`와 같은 의미·형식으로 직접 이관해 신원 continuity를
  유지한다. 두 current route version의 `valid_from`만 fail-closed로 후방 확장하므로 history/live가
  같은 version ID를 쓰며 cross-version query bridge는 필요하지 않다. source provenance와 online
  forecast backlog 격리는 여전히 선행해야 한다.
- 개인 schedule disable 뒤 두 List와 두 byte audit가 동일해 terminal source freeze가 확정됐다.
  full record/raw history는 299,937 objects / 2,346,432,060 bytes이고 bijection 오류와 base late object는
  모두 0이다. audit 판정은 `TERMINAL_SOURCE_FREEZE_CONFIRMED`이며 실제 import 승인을 뜻하지 않는다.
- primary transfer는 19개 base 날짜 shard + 1개 catch-up delta shard의 content-addressed
  `jsonl.zst`를 resumable rsync-over-SSH로 옮기는 방식이다. presigned HTTPS GET은 fallback이다.
  이 감사에서는 archive/remote file을 만들거나 전송하지 않았고 academy에 접속하거나 쓰지 않았다.

## 1. 증거 경계

source는 개인 계정 `827325854159`, target은 academy 계정 `843255971531`이다. source에서 실행한
AWS 작업은 Lambda List/Get/config와 collector S3 List/Get 및 암호화 설정 Get뿐이다. SSM 값,
환경값, secret, academy 자격, academy RDS 연결은 읽거나 시도하지 않았다.

target 정보는 사용자가 EC2 내부 read-only transaction으로 제공한 기준점이다.

| target 기준 | 값 |
|---|---:|
| engine/class | PostgreSQL / `db.t4g.micro` |
| Flyway | V1..V12 success |
| observation_batch | 303 |
| vehicle_observation | 8,896 |
| route / route_version / route_stop | 1 / 1 / 85 (초기 기준점, 3330 only) |
| response range | `2026-09-02T10:27:52.390820Z` ~ `2026-09-02T11:43:58.008989Z` |
| model_deployment / seat_forecast / stop_demand_statistics | 0 / 0 / 0 (temp activation 전 snapshot) |

이후 사용자 제공 d856 read-only 보고에서 route 3330은 route/version id `1/1`, 1650은 `2/2`이고
두 reference가 exact임을 확인했다. 최신 route count는 route 2 / route_version 2 / route_stop 174다.
이 감사는 그 target을 직접 조회하지 않았다.

`2026-09-02T11:55:04.729493Z`에는 임시 model deployment id 1이 활성화됐다. 사용자 제공 시점별
`seat_forecast` count는 21:15 KST 63,343행, 22:23 KST 151,523행이다. 서로 다른 시점의 수이므로
현재값이나 cleanup 대상 수로 재사용하지 않으며, 실행 직전 read-only snapshot에서 다시 고정해야 한다.
같은 시점의 `stop_demand_statistics` exact count는 제공되지 않아 추정하지 않는다.

source bucket 기본 암호화는 SSE-S3 `AES256`이고 KMS key는 설정되지 않았다. versioning, Object Lock,
lifecycle도 설정되지 않았다. records/raw는 같은 cutoff의 전후 manifest가 일치했지만 `control` counter는
mutable하므로 bucket 전체 manifest를 불변 근거로 쓰면 안 된다.

### 1.1 target dev delta

`ed2cf742→d856d108`은 정확히 네 파일만 바꾼다. `buildspec.yml`, deploy rehearsal,
`deploy/scripts/common.sh`는 cache와 배포 잠금 재시도 변경이라 field mapping, trainer, import row 계약에
영향이 없다. `worker-app/application.yml`만 route `234000050`을 추가해 최신 dev가 3330과 1650을 모두
수집하도록 바뀐다. Flyway V1..V12 path/hash, entities, collector normalization, processor SQL은 그대로다.
따라서 기존 mapping/count/identity/as-of 결과는 유지된다. 1650 seed는 이미 존재하는 current id=2의
exact-content precondition을 증명하는 reference evidence이며 target insert payload가 아니다. 정본은
[target-dev-delta.json](target-dev-delta.json)이다.

## 2. 전체 inventory와 무결성

### 2.1 관측한 bucket 전체

`2026-09-02T11:48:55.905Z`의 초기 whole-bucket snapshot은 298,648 objects /
2,803,156,460 bytes였다. 아래에는 mutable `control`과 이관 외 family도 포함되므로 terminal record/raw
freeze와 범위가 다르며 최종 source authority로 쓰지 않는다.

| family | objects | bytes | 이관 판정 |
|---|---:|---:|---|
| records | 149,302 | 1,832,464,583 | 검증·변환 대상 |
| raw | 149,175 | 503,364,961 | 검증 근거, RDS 미적재 |
| derived | 119 | 259,681,603 | S3-only |
| deployment | 9 | 201,039,293 | S3-only |
| rehearsal | 21 | 6,598,514 | S3-only |
| control | 21 | 6,673 | mutable, S3-only |
| audit | 1 | 833 | S3-only |

### 2.2 immutable base

base는 KST partition 2026-08-14..2026-09-01을 각 날짜 다음 날 00:15 KST에 동결한 것이다.
응답 시각 범위는 `2026-08-14T07:38:45.475Z`..
`2026-09-01T15:00:03.029Z`다. 마지막 값이 다음 KST 날짜로 3초 넘어가는 것은 request-start
partition을 쓰는 collector 특성이고, 검증된 자정 crossing 4건 중 하나다.

| 항목 | inventory | 검증·quarantine 후 |
|---|---:|---:|
| record documents | 142,156 | 142,129 |
| raw documents | 142,031 | 142,004 |
| raw-less records | 125 | 125 |
| observations | 2,324,944 | 2,324,399 |
| record+raw bytes | 2,206,270,988 | source bytes는 그대로 보존 |

검증 결과:

- JSON/partition/route/hash/byte count/envelope/record↔raw row mismatch: 모두 0건
- missing raw, orphan raw, duplicate raw reference: 모두 0건
- 전역 duplicate record ID: 0건
- station roster mismatch: 27 records, mismatch row 27개, record 전체 관측 545행 quarantine
- `stateCd`/stop identity 때문에 current normalization이 행 단위로 버릴 값: 0행
- reported unknown seats: 1,670행; `REPORTED_UNKNOWN`으로 변환
- crowd 1..4 보존: 2,123,093행; 0을 null로 접는 행: 201,851행
- semantic failure: 964 records (`API_ERROR` 124, `HTTP_ERROR` 20,
  `INCOMPLETE_ENVELOPE` 695, `TRANSPORT_ERROR` 125)
- late object: next-day 00:15 KST 뒤에 생성된 base record/raw 0개
- key timestamp 대비 S3 생성 지연: record p50 0.647s / p99 7.588s / max 17.508s,
  raw p50 0.642s / p99 6.746s / max 16.590s

semantic failure는 검증 실패가 아니다. 호출 공백·상류 오류를 설명하는 batch로 이관하고 관측 행만 0개다.

### 2.3 일·노선·schema 분포

19일 base에서 두 노선 record는 각각 71,078개다.

| route | records | observations | station mismatch rows |
|---|---:|---:|---:|
| 1650 | 71,078 | 1,207,025 | 4 |
| 3330 | 71,078 | 1,117,919 | 23 |

일별 record는 개시일 842개에서 최대 8,557개, 관측은 20,145행에서 최대 167,548행이었다.
모든 record의 선언 schema는 `1.0.0` 하나다. payload 형태는 정상 다건/단건/빈 응답/불완전 envelope/
gateway error/raw-less transport error 차이 때문에 record shape 7개, response shape 5개로 갈린다.
날짜×노선의 전체 수와 각 shape fingerprint는 [inventory.json](inventory.json)의
`daily_route_distribution`, `inventory.by_partition`, `record_schemas`,
`response_envelope_schemas`가 정본이다.

### 2.4 cutoff catch-up과 live overlap

personal schedule을 `2026-09-02T13:18:57.240Z`에 disable한 뒤 KST 2026-09-02 partition을 두 번
List하고 두 번 byte-level 검증했다. 두 번째 canonical audit는 `13:23:40.988Z`에 시작해
`13:24:20.855Z`에 끝났고 첫 audit와 실행 시각 외 모든 값이 같다. 각 route의 source 자동 이관 경계는
target current `route_version.valid_from`을 보수적으로 사용한다.

| route | current version | source 자동 이관 조건 | source 마지막 accepted | target 증거 |
|---|---:|---|---|---|
| 3330 | 1 | `response_received_at < 2026-09-02T10:27:51.330754Z` | `10:27:45.315Z` | known target first `10:27:52.390820Z`, 간격 7.07582초 |
| 1650 | 2 | `response_received_at < 2026-09-02T12:49:33.041299Z` | `12:49:31.467Z` | version open까지 1.574299초; exact target first는 미확인 |

route별 재산출 전의 legacy global seam은 source `10:27:46.514Z`와 target
`10:27:52.390820Z` 사이 5.87682초였다. 이는 역사적 연속성 증거로만 보존하며 automatic import 권위는
위 route별 경계가 대체한다.

| 구간 | inventory records | accepted records | accepted raw | observations | 판정 |
|---|---:|---:|---:|---:|---|
| route별 authority 이전 catch-up | 7,065 | 7,064 | 7,062 + raw-less 2 | 136,909 | 자동 이관 |
| route별 authority 이상 overlap | 811 | 811 | 811 | 14,924 | 자동 이관 제외 |

catch-up은 3330 3,249 records/58,234 observations와 1650 3,815/78,675로 나뉜다. 1650 roster
mismatch record 1개/관측 24행은 추가 quarantine했다. overlap은 3330 689/14,364, 1650 122/560이며,
그 안의 HTTP 429 failure record 190개는 관측 0행의 실패 이력이다. overlap 전체는 target gap이 별도
read-only 증거로 입증되지 않는 한 S3-only reconciliation evidence다.

immutable base와 route별 catch-up을 합친 자동 이관량은 accepted batch 149,193,
accepted raw 149,066 + raw-less 127, observation 2,461,308이다. 이 범위에서 스캔한 source는
record 149,221 / raw 149,094 objects, 2,332,573,220 bytes다.

terminal freeze digest는 범위가 서로 다르므로 다음처럼 구분한다.

| SHA-256 범위 | record/raw objects | bytes | digest |
|---|---:|---:|---|
| immutable base, KST 2026-08-14..09-01 | 142,156 / 142,031 | 2,206,270,988 | `db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9` |
| active partition, KST 2026-09-02 전체 | 7,876 / 7,874 | 140,161,072 | `f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e` |
| full record/raw history, 위 두 범위 결합 | 150,032 / 149,905 | 2,346,432,060 | `ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8` |

data-analysis의 `final-source-closure.json`이 기록한 `f0decee3…a86e`는 active partition digest라서 이
감사의 active digest와 일치한다. `db473053…35b9`와 `ad7dca…fad8`는 각각 base와 full-history 범위다.
terminal LastModified는 `2026-09-02T13:20:02Z`, 마지막 source response는 `13:20:01.561Z`이고,
record/raw bijection 오류와 base late object는 모두 0이다.

## 3. field mapping과 민감 경계

완전한 행별 판정은 [field-mapping.json](field-mapping.json)에 있다. 핵심은 다음과 같다.

| source | target | 판정 | 처리 |
|---|---|---|---|
| route ID/name | `route`, `observation_batch.route_version_id` | complete/transform | 기존 route와 exact resolve; blind insert 금지 |
| request/response time | batch `requested_at`, `response_received_at` | complete | response time이 관측 권위 |
| classification/API code | batch outcome/failure/result | transform | current enum으로 결정 변환 |
| strategy version | batch `collection_strategy_version` | complete | 그대로 보존 |
| buses row order | `source_row_number` | complete | 0-based 순서를 그대로 보존 |
| stationSeq/stateCd | stop/running/passed order | transform | state=1이면 passed=seq-1 |
| remainSeatCnt | seats/reason | transform | 음수→`REPORTED_UNKNOWN` |
| crowded | `crowd_level` | transform/loss | 1..4만 보존, 0→null |
| lowPlate/routeType/tagless | 대응 current column | complete | 모델 미소비지만 최대 보존 |
| vehId | `vehicle_id` | complete+sensitive | 같은 text 형식으로 private archive/RDS에 직접 이관 |
| plateNo | `plate_number` | complete+sensitive | current 모델/processor가 쓰지 않아 의도적 S3-only |
| source HMAC | current column 없음 | unsupported+sensitive | 원값 미복사; canonical digest 재생성에만 사용 가능 |
| raw envelope/body/header/message/quota details | current column 없음 | unsupported/loss | 검증 후 S3-only |

base 2,324,944행에서 vehId와 plateNo는 모두 존재했고, private vehicle identity는 78개,
vehId↔source vehicle HMAC 양방향 충돌은 0건, batch 내부 duplicate vehicle은 0건이었다. 값 자체는
어떤 산출물에도 남기지 않았다.

`vehicle_id`는 `JdbcVehicleTrajectoryRepository`, capacity as-of, preceding vehicle,
`JdbcArrivalObservationRepository`, stop-demand 집계에 필요하다. source와 current target의 의미·문자열 형식이
같다는 사용자 결정을 적용해 2,461,308개 이관 observation 모두 non-null `vehicle_id`를 보존한다.
`plate_number`는 current processor feature 소비가 없어 모든 historical row에서 null이고 원 S3에만 남는다.

archive는 더 이상 비식별 자료가 아니라 `PRIVATE_SENSITIVE_NORMALIZED`다. vehicle 값은 SSH 암호화 전송,
local/remote directory `0700`, file `0600`, `umask 0077` 경계 안에서만 존재한다. logs, fixture, docs 예시,
stdout, manifest, receipt에는 값이 한 건도 나가면 안 된다. HMAC/pseudonym/raw body/plate/service-key 자료는
archive에 들어가지 않는다. import 완료 뒤 archive 삭제도 별도 사용자 승인 사항이다.

## 4. natural identity와 target 충돌

| 후보 | base collision extras/rate | 결론 |
|---|---:|---|
| S3 object key | 0 / 0% | RDS column이 없고 다른 key로 복제된 동일 호출을 못 잡음 |
| source record UUID | 0 / 0% | provenance에는 적합, semantic replay는 못 잡음 |
| route + request start ms | 0 / 0% | 실제 충돌 없음 |
| route + request start second | 0 / 0% | current attempt-key와 유사, 실제 충돌 없음 |
| route + scheduled time + round | 98 / 0.0689403% | 부적합 |
| invocation + route + round | 98 / 0.0689383% | 부적합 |
| raw response SHA-256 | 731 / 0.514676% | 반복 응답이 정상이라 부적합 |
| semantic batch digest | 0 / 0% | 권장 |

권장 이중 키는 `(source_account, source_record_id)` provenance PK와
`(source_account, semantic_batch_digest)` UNIQUE다. digest는 source account, source route ID,
request/response instant, raw-body digest, round index를 versioned length-prefix codec으로 묶는다.
RDS `attempt_key`는 `s3v1:<64hex>`로 둔다. live code의 `<routeId>-<instant>` namespace와 문법부터
갈리므로 기존 303 batch와 충돌하지 않고, 같은 archive 재실행도 no-op으로 만들 수 있다.
semantic/import digest tuple에는 vehicle/plate 값을 넣지 않고, 원문을 encoding·truncation해 되살릴 수 있는
형태도 금지한다. `response_sha256`은 이미 검증된 one-way body digest로만 사용한다.

vehicle unique index는 batch 내부 범위라 새 batch ID를 쓰면 기존 8,896 rows와 충돌하지 않는다.
plate는 unique가 아니다. route content는 §5에서 닫혔지만 `route.public_route_id` UNIQUE와
`route_version` valid-time exclusion, original `valid_from`, current version count를 실제로 통과하는지는
validity update 전 read-only dry-run gate다.

필수 schema 변경:

- `migration_source_record`: source account/record UUID/semantic digest/archive digest/imported batch/버전/시각
- `observation_batch.ingestion_origin`: `LIVE|S3_BACKFILL`; online pending forecast query/index는 LIVE만 선택

직접 vehicle identity에는 새 column이 필요 없다. 3330 history/live는 route_version_id=1, 1650
history/live는 route_version_id=2를 그대로 공유한다. 따라서 current processor의 route-version-scoped
trajectory/capacity/arrival 조회를 잇는 별도 adjacent-version bridge는 필요하지 않다.

`ingestion_origin`이 없으면 140k여 개의 성공 backfill batch가 `forecast_completed_at IS NULL` pending
queue에 들어간다. `forecast_completed_at`을 허위로 채우는 것보다 origin으로 격리하는 것이 맞다.

## 5. route mapping과 seed

사용자 제공 read-only 보고서와 SHA-256은 [route-mapping-summary.json](route-mapping-summary.json)에
고정했다. 최신 target은 3330 route/version id `1/1`과 1650 `2/2`를 모두 갖고 있고 route 2 /
version 2 / stop 174다. 각 route에는 current version 하나만 있으며 둘 다 source reference의 ordered
stop ID/name, turn, boarding policy와 exact match한다.

3330 current version id 1의 `valid_from`은 `2026-09-02T10:27:51.330754Z`, stops 85, turn 43,
nonboarding 7, name-inclusive content digest는
`91749006e76e5f822c1c2e241b37fae4eba6941e217dcba2928c6e7e8ffdae5d`다. content blocker는 없고
validity만 source history를 덮지 않는다. exact digest/turn/policy/stop count/only-one-version precondition을
모두 만족한 한 transaction에서 id 1의 `valid_from`만 accepted source minimum
`2026-08-14T07:38:45.475Z`로 확장한다.

1650 current version id 2의 `valid_from`은 `2026-09-02T12:49:33.041299Z`, stops 89, turn 44,
nonboarding 24, content digest는
`f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc`다. 분석 repository의 두
독립 cache는 2026-08-19 GBIS 검증 source와 일치하고 protocol mismatch 0·first mismatch 없음이다.
[route-seed-1650.json](route-seed-1650.json)은 이 precondition을 재현하는 reference evidence일 뿐
target insert payload가 아니다. 추가 GBIS 호출은 0회다. id 2의 `valid_from`만 accepted source minimum
`2026-08-14T07:38:46.604Z`로 확장한다.

두 update는 하나라도 precondition이 다르면 0건 변경으로 실패해야 하며, route/route_version/route_stop을
추가하지 않는다. 원래 `valid_from` 두 값, 새 값, route/version ID, content/order digest, stop/turn/
nonboarding count, import ledger identity와 rollback 순서를
[route-migration-receipt.schema.json](route-migration-receipt.schema.json) 형식으로 먼저 보존한다. rollback과
실제 update는 각각 별도 승인 대상이다. source earliest보다 앞선 미증명 기간으로 확장하지 않는다.

station mismatch 27행/accepted observations 2,324,399행은 0.0012%이며 08-21, 08-24, 08-31,
09-01 네 날짜에 간헐적으로만 나타났다. 개편이 아니라
`STATION_ID_MISMATCH_AGAINST_GBIS_2026_08_19` anomaly로 whole-record quarantine하고 날짜·노선·건수를
import receipt에 남긴다.

route-version identity는 digest 하나가 아니라 route/public identity + `valid_from` + `valid_to` + content
digest다. 이번 선택은 adjacent duplicate version을 만들지 않으므로 각 route의 history/live가 동일한
current version ID를 사용한다. boundary acceptance는 vehicle 값을 내보내지 않고 route별 shared
`vehicle_id` aggregate, exact version ID, 양쪽 시간 범위만 검증한다.

## 6. archive와 transfer

실제 private normalized serializer를 디스크가 아닌 discard sink로 흘려 측정했다.

| base archive | bytes |
|---|---:|
| canonical JSONL, uncompressed | 666,925,584 |
| zstd level 3, single stream | 19,589,490 |
| gzip level 6, 19 date shards 합 | 24,219,244 |
| 가장 큰 날짜 shard, uncompressed/gzip | 46,725,330 / 1,572,956 |

이 측정은 raw `vehicle_id`를 archive row에 넣되 디스크/stdout에는 쓰지 않고 compressor discard sink로만
흘린 결과다. 따라서 base는 KST 날짜당 1 shard, 총 19개가 자연스럽다. catch-up 7,064 records/
136,909 observations는 같은 비율로 35~45MB uncompressed, 0.95~1.4MB zstd의 delta shard 1개로
예상한다. 한 날짜가
64MiB uncompressed 또는 16MiB compressed를 넘을 때만 semantic digest 순서에서 batch 경계로 나눈다.

primary 절차:

1. personal side에서 source manifest를 재검증하면서 canonical date shard와 manifest를 만든다.
2. 파일명은 `dt=YYYY-MM-DD_NNN_sha256-<digest>.jsonl.zst`이고 compressed/uncompressed SHA-256,
   row counts, response range를 manifest에 넣는다.
3. rsync-over-SSH는 partial file을 유지해 재개하고, remote final rename 전 compressed hash를 검증한다.
4. EC2에서는 expanded 파일을 만들지 않고 한 shard씩 stream-decompress → schema validate → staging/COPY 또는
   batched insert → transaction commit → aggregate receipt 순으로 처리한다.
5. fallback presigned GET도 같은 shard/hash를 쓰며 URL은 bearer credential로 취급해 저장·로그하지 않는다.

archive와 partial file은 양쪽 모두 `umask 0077`, directory `0700`, file `0600`을 강제한다. rsync도
remote mode를 같은 값으로 고정해야 한다. vehicle 값은 row stream 외 표면에 출력하지 않으며, 성공 뒤
local/remote archive를 지우는 작업도 자동화하지 않고 정확한 경로·hash·import receipt를 제시한 뒤 별도
승인을 받는다.

27GiB free disk는 약 21MB compressed archive에 충분하다. 다만 available 802MiB/no swap이므로 importer
RSS 256MiB, decompression buffer 8MiB, JDBC batch 1,000 observation rows, transaction 10,000 rows,
동시 shard 1개를 hard limit로 둔다. free memory 384MiB 미만 또는 disk 5GiB 미만이면 시작/계속하지 않는다.

## 7. RDS row/space/time

Flyway V1..V12를 적용한 disposable PostgreSQL 18에 합성 142,129 batch와 2,324,399 observation을
넣어 측정했다. source row나 identifier는 사용하지 않았다.

| relation | heap | indexes | total |
|---|---:|---:|---:|
| observation_batch | 36,388,864 | 37,756,928 | 74,178,560 |
| vehicle_observation | 284,655,616 | 359,522,304 | 644,268,032 |
| 합계 | 321,044,480 | 397,279,232 | 718,446,592 |

합성 측정의 입력은 immutable base 크기이고 terminal catch-up을 포함한 실제 자동 이관 계획은
149,193 batch / 2,461,308 observation이다. 사용자 제공 초기 target 기준점에 단순 가산한 하한은
149,496 / 2,470,204이며, route / route_version / route_stop은 update 전후 2 / 2 / 174다. 초기
기준점 이후 live 증분은 실행 직전 read-only snapshot으로 별도 더한다.

index가 relation total의 55.3%이고 heap 대비 123.7%다. direct `vehicle_id` index는 이 측정에 이미
포함됐고 provenance/origin column과 index는 추가 30~100MB를 예상한다. current rows, page 여유,
bloat를 포함한 persistent 범위는 약 0.93~1.3GB다. WAL/checkpoint/temp에는 별도 1.5~4GB를
잡고, RDS free storage 5GiB 미만이면 중단한다.

로컬 base 크기 약 2.47M logical insert는 약 30초였지만 운영 하한일 뿐이다. terminal 계획은 약
2.61M logical insert다. source full byte 검증은 실측 984.833초였다.
RDS는 단일 importer 기준 20~90분, live load/I/O가 불리하면 최대 4시간 envelope로 잡는다.

AWS 공식 사양상 `db.t4g.micro`는 2 vCPU/1GiB이고 burstable class이며 RDS T4g는 Unlimited mode다.
현재 측정 index만 약 397MB라 active index와 DB working set을 RAM에 함께 두기 어렵다. CPU credit,
freeable memory, write latency, queue depth, free storage, connections를 매 shard 전후 확인해야 한다.
AWS의 bulk-import 문서는 `\copy`, WAL/checkpoint 및 memory 조정을 다루지만, live DB에서 backup,
autovacuum, synchronous commit을 바꾸는 것은 이번 범위도 승인도 아니다. 여기서는 작은 transaction과
단일 writer로 제한한다.

- [RDS db.t4g class types](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.Types.html)
- [RDS instance hardware specifications](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.Summary.html)
- [RDS PostgreSQL import guidance](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Procedural.Importing.html)

## 8. immutable cutoff, late delta, continuity

1. base manifest는 KST date별 next-day 00:15 freeze로 고정한다.
2. import 직전 old partitions를 다시 List하고 base inventory digest와 비교한다. 새 object가 있으면 원래 KST
   date로 `LATE_DELTA`를 만들고 base manifest + previous delta manifest hash에 체인한다.
3. 모든 delta record는 source UUID와 semantic digest 양쪽으로 dedupe한다. 지금 측정된 late object는 0개다.
4. KST 2026-09-02는 route 3330에서 `response_received_at < 10:27:51.330754Z`, route 1650에서
   `< 12:49:33.041299Z`만 source catch-up 권위다.
5. target은 각 route 경계 이상을 권위로 가진다. S3 overlap 811 records/14,924 observations는 import하지
   않으며 target gap이 read-only 증거로 확인된 경우에만 새 승인과 새 content-addressed manifest로 예외를
   검토한다.
6. shard commit은 atomic하고 receipt가 없는 shard는 trainer snapshot에 보이지 않는다.
7. late delta가 생기면 기존 학습 receipt를 수정하지 않고 새 dataset/training version을 만든다.
8. personal schedule disable 뒤 2회 List와 2회 byte audit가 동일한 terminal freeze를 만들었다. full-history
   digest는 `ad7dca…fad8`, active-partition digest는 `f0decee3…a86e`이며 서로 다른 범위다. schedule
   재활성화, source archive 삭제, 실제 import는 이 freeze 판정에 포함되지 않고 각각 별도 승인이다.

## 9. RDS trainer 조건

[trainer-read-contract.json](trainer-read-contract.json)이 machine-readable 정본이다.

- `REPEATABLE READ READ ONLY` snapshot에서 DB snapshot ID, `(response_received_at,batch_id)`
  high-water, row counts, Flyway checksums, ordered base/delta hashes를 함께 고정한다.
- capacity는 prediction `(time,id)` 이하의 과거 관측만 사용한다. current runtime은 이미 이 cursor를 쓴다.
- trajectory는 30분 window의 empty/failure batch까지 보아 gap을 보존한다.
- arrival label만 prediction 이후를 볼 수 있고 feature는 미래를 보지 않는다.
- statistics generation은 `data_until <= prediction observed_at`인 revision만 고른다.
- route roster는 observation 시각에 유효한 version으로 결합한다.
- normalization/strategy/origin/route-reference/identity-algorithm/exclusion-policy/import-manifest를 모델 bundle
  provenance에 넣는다.

## 10. temporary model 오염 방지

고정 identity:

- model_deployment.id: `1`
- release: `salmonbus-d57370be9195520e`
- bundle digest: `d57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a`
- calculation: `seat-feature-contract-v4-1-2026-09-02`
- activated: `2026-09-02T11:55:04.729493Z`

사용자 제공 `seat_forecast` snapshot은 21:15 KST 63,343행, 22:23 KST 151,523행이다. 이 수들은
시점이 다르고 계속 증가할 수 있으므로 cleanup 대상의 현재 count로 간주하지 않는다.

모든 trainer/evaluator/cell backfill/migration measurement는 exact tuple의 forecast를 명시 제외한다.
같은 시간의 observation은 정상 수집 원천이라 보존·학습한다.

추가 오염 경로가 있다. `StopDemandStatisticsJob`의 계산 규칙은
`observed-max-capacity-v1`이고, SETTLED forecast를 carrier로 삼아 cell generation을 만든다. prediction
숫자를 합산하지 않아도 carrier lineage가 temp이므로 공식 입력으로 쓸 수 없다. activation 직전
`stop_demand_statistics=0`이라는 기준점을 근거로 다음 half-open window를 temp lineage로 본다.

```text
computed_at >= 2026-09-02T11:55:04.729493Z
AND computed_at < formal_cutover_at
```

정식 cutover 직전 statistics job/settlement를 quiesce하고, 이 window에서 temp calculation version 또는
`observed-max-capacity-v1`인 세대를 `(route_version_id, calculation_version, revision, data_until,
computed_at, row_count)`로 동결한다. revision/data_until/computed_at 범위와 canonical manifest SHA-256도
같이 남긴다. cutover 전에는 open-ended activation window로, cutover 뒤에는 이 exact frozen set으로
trainer/evaluator/cell/seed에서 제외한다. 계약은
[temp-generation-manifest.schema.json](temp-generation-manifest.schema.json)에 있다.

정식 bundle/seed는 반드시 다른 calculation version을 써야 한다. 정식 교체 성공 후에도 먼저
[temp_release_cleanup_dry_run.sql](scripts/temp_release_cleanup_dry_run.sql)로 exact identity, RETIRED 상태,
formal ACTIVE, temp forecast/statistics 대상 건수, observation invariant를 read-only로 제시해야 한다.
이 스크립트는 확정된 UTC cutover를 psql 변수 `formal_cutover_at`으로 받으며 값이 없으면 실행을 중단한다.
별도 사용자 승인 뒤에만 temp `seat_forecast`와 frozen generation set에 exact join되는
`stop_demand_statistics`를 제거한다. `calculation_version`만으로 전체 삭제하는 것은 금지한다.
deployment id 1은 RETIRED lineage로 남기고 `forecast_completed_at`은 null로 되돌리지 않는다.

cleanup 전후 반드시 같은 값이어야 하는 것은 다음 네 가지다.

- observation_batch count
- vehicle_observation count
- minimum observation_batch.response_received_at
- maximum observation_batch.response_received_at
- 공식 trainer/evaluator/seed가 읽은 frozen temp generation rows = 0

이번 감사에서는 DELETE나 파일 삭제를 하지 않았다.

## 11. acceptance와 실행 전 blocker

구현 담당자는 [archive-record.schema.json](archive-record.schema.json),
[archive-manifest.schema.json](archive-manifest.schema.json),
[acceptance-fixture.json](acceptance-fixture.json)을 그대로 사용할 수 있다. fixture 값은 모두
2000년 시각과 고정 synthetic digest를 쓴 비-source 데이터다. 민감 `vehicle_id` 값은 fixture 파일에
두지 않고 validator가 메모리에서만 synthetic 값을 주입해 archive schema를 검사한다. Route 계약은
[route-mapping-summary.json](route-mapping-summary.json)과 [route-seed-1650.json](route-seed-1650.json)이
정본이다. terminal source closure는 [cutover-readiness.json](cutover-readiness.json), 두 List receipt,
두 byte-audit receipt와 canonical [continuity-window.json](continuity-window.json)이 함께 증명한다.

다음이 닫히기 전에는 import를 실행하면 안 된다.

1. target current route/version/stop exact content, original valid_from, only-one-version precondition dry-run
2. 두 route별 direct private vehicle_id, exact current version ID와 boundary time-range aggregate test 통과
3. provenance/origin schema migration과 online forecast query/index 수정
4. temp deployment와 frozen observed-max carrier-generation exclusion을 trainer/evaluator/cell SQL에 적용
5. target RDS/EBS encryption, KMS(해당 시), free storage/CPU/memory/load 확인
6. content-addressed export/import 구현의 fixture 통과와 idempotent retry 증명
7. importer manifest가 terminal full-history digest와 route별 authority/count를 정확히 고정했는지 확인

## 12. 검사 명령

```bash
PYTHONDONTWRITEBYTECODE=1 python3 audit/s3-rds-migration/scripts/validate_artifacts.py
jq empty audit/s3-rds-migration/*.json
(cd audit/s3-rds-migration && shasum -a 256 -c SHA256SUMS)
git diff --check
git status --short
```

`target_preflight_readonly.sql`은 Flyway V1..V12를 적용한 disposable PostgreSQL 18.6의 빈 DB와
두-route 합성 DB에서 read-only JSON 1행으로 통과했다. `boundary_continuity_readonly.sql` v2도 같은
schema에 required `ingestion_origin` column만 추가한 빈 DB/합성 DB에서 두 route별 결과로 통과했다.
합성 vehicle 값은 local transaction 안에서만 사용했고 출력하지 않았다. academy target에는 연결하지 않았다.

source를 다시 읽는 전수 감사는 기존 output과 다른 새 경로를 지정해 실행한다.

```bash
zsh audit/s3-rds-migration/scripts/run-inventory-audit.sh \
  /Users/idonghun/IdeaProjects/salmonbus-analysis \
  /tmp/salmonbus-inventory-rerun.json
```

## 13. 남은 불확실성

- 두 current version id 1/2의 fail-closed `valid_from` update 및 rollback receipt read-back 결과
- route별 boundary 양쪽 private `vehicle_id` 교집합 수와 exact current version ID/time-range 집계
- 1650 exact 최초 live `observation_batch.response_received_at`; 현재 cutoff는 version open 시각을 쓴 보수값
- target current `attempt_key`, private column null/distinct/within-batch duplicate 집계
- RDS allocated/free storage, storage type/IOPS, encryption/KMS, CPU credit/load/latency
- 11:55 UTC temp 활성화 뒤 실행 직전 forecast/statistics exact counts와 frozen generation 집합
- formal trainer 구현과 snapshot receipt 형식

이 불확실성은 academy를 추정 접근해 메우지 않았다. 필요한 접근은 academy 내부 RDS에 대한
`REPEATABLE READ READ ONLY` 집계 session이며 목적은 위 precondition/count/time range와 storage 상태를
고정하는 것이다. 이 워커는 EC2 SSH/RDS tunnel을 시도하지 않았다. 기존 SSH가 현재 IP에서 차단된 경우
대안은 사용자가 EC2 내부에서 제공된 SQL을 실행해 aggregate receipt만 전달하거나, 이미 승인·구성된 SSM
Session Manager를 쓰거나, 별도 승인으로 security group에 현재 IP를 제한적으로 추가하는 것이다. 공개 HTTP
health/board endpoint는 서비스 복구 확인에는 쓸 수 있지만 DB 내부 precondition/count를 대체하지 못한다.

## 14. 변경 파일과 checkpoint

변경은 이 worktree의 `audit/s3-rds-migration/` 아래 미추적 audit 산출물 34개(상위 24, scripts 10)뿐이다.
commit, push, PR, deploy, archive 생성·전송, AWS/RDS write, academy 연결은 하지 않았다. 설계 경계 확정,
source 전수 검증, 사용자 결정 반영, validator/SHA 검증 시점은 공유 checkpoint log에 남겼다. 체크섬 파일
자신을 제외한 33파일의 SHA-256은 [SHA256SUMS](SHA256SUMS)가 정본이다.
