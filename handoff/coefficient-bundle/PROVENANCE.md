# Provenance

## 조사 기준 시각

- dev HEAD 관측: 2026-09-02 11:45:26 KST (`2026-09-02T02:45:26Z`)
- S3 원천 inventory 시작: 2026-09-02 11:57:49 KST
- AWS 인프라 재관측: 2026-09-02 12:38:18 KST
- 모든 AWS·GitHub·EC2 관련 조사는 읽기 전용이었다.

## 코드 정본과 커밋

| 역할 | 저장소/위치 | 조사한 commit | 판정 |
|---|---|---|---|
| v4-1 1차 정본 | `woowacourse-teams/2026-salmonbus`, local `IdeaProjects/2026-salmonbus`, branch `dev` | `b239691512dc5c498ae7013cd786e0b627f0c010` | API DTO·service·test와 Java bundle 소비 계약의 기준 |
| 운영 bundle producer/runtime | `DongKey777/salmonbus-analysis`, remote ref `origin/main` | `c5ff99da81ed0af9916bd5aa5115d89f49258e2c` | Python A18 계산·검증·배포 artifact 기준 |
| 최종 적합 계약 도입 | 같은 저장소 | `7946daccc79fec6b8e372027f951bfabfa714f74` | final adapter와 2026-08-23 source closure 도입 |
| 배포된 evaluator Lambda source | deployment ZIP member hash와 Git history 대조 | `284010845562fed9cff9b243fb1a109407fdd716` | 일일 평가기; final adapter 파일은 ZIP에 없음 |
| 보조 사이트 문서 | `DongKey777/salmonbus-api-docs`, `origin/main` | `a0924128467e59b54efa00421b8d6a1db5f6d5f0` | 보조 설명. 페이지 자신도 dev DTO/test를 정본으로 선언 |
| `model-api-site`, `model-api-site-2` | Paseo worktree 두 개 | `4ad46071f742138cb473e10c62f8a980eb611c5c` | tracked v4-1 계약 문서 없음. 전자는 untracked site plan만 있어 정본으로 쓰지 않음 |

dev working tree에는 기존 사용자 소유 untracked `api-contract/`, `model-handoff/`, `review-replies/`가
있었다. 읽기만 했고 commit provenance로 인용하지 않았다. `study-deploy-infra`는 열거나 수정하지 않았다.

## dev v4-1 코드 근거

- API 진입점: `backend/api-app/.../board/controller/BoardController.java:15-30`
- API 조립·방향 변환·값 검증: `.../board/application/BoardQueryService.java:38-185`
- DTO와 null 생략: `.../board/dto/BoardResponse.java:7-29`,
  `ApproachingVehicleResponse.java:6-23`
- DB mapper: `.../board/persistence/jpa/JpaBoardQueryRepository.java:38-113`,
  `SeatForecastJpaEntity.java:31-64`
- API fixture/test: `BoardApiContractTest.java:52-123`, `BoardQueryServiceTest.java:92-192`
- bundle 로딩: `backend/worker-app/.../seatdistribution/BundleStartupLoader.java:54-90`,
  `BundleLoader.java:32-251`, `BundleManifestReader.java:43-79`
- tensor contract: `BundleTensor.java:17-78`
- 31열: `processor/SeatForecastDesignMatrix.java:43-214`
- runtime 기본값: `backend/worker-app/src/main/resources/application.yml:43-64`
- 테스트 dummy: `.../seatdistribution/DummyBundle.java:16-95,118-180,297-321`

## AWS 데이터 흐름 증거

### Collector

- 함수: `salmonbus-collector`, Python 3.13/arm64, Active.
- deployed ZIP: 8,683B, SHA-256
  `12474ad8455a41375fb61dd96c428d24a3f909e2dcaeed64ecc4db34934ec63b`.
- ZIP은 `lambda_function.py` 하나다. GitHub code search와 local repository 검색에서 동일 source를
  찾지 못했으므로 repository commit은 `UNKNOWN`; 배포 ZIP SHA와 Lambda `CodeSha256`가 현재 증거다.
- 환경 변수는 이름만 확인했다: `BUCKET_NAME`, `HMAC_KEY_PARAMETER`,
  `SERVICE_KEY_PARAMETER`, `QUOTA_MODE_PARAMETER`, `MIN_CALL_INTERVAL_SECONDS`. 값은 조회·출력하지 않았다.
- Scheduler: `salmonbus-collector/salmonbus-adaptive-heartbeat`, ENABLED,
  `cron(* * * * ? *)`, `Asia/Seoul`, retry 0, event age 60s.
- 함수의 최신 CloudWatch event와 S3 record가 조사 당일까지 계속 전진했다.

### Model evaluator Lambda

- 함수: `salmonbus-model-evaluator`, Python 3.13/arm64, 4,096MB, timeout 900s.
- 배포 ZIP source member 세 개(`pipeline.py`, `models.py`, `protocol.json`)가 모두 analysis commit
  `2840108`과 byte SHA-256이 일치했다.
- `final_bundle_adapter.py`는 ZIP에 없다. 즉 일일 Lambda는 계수 번들 생산자가 아니다.
- EventBridge rules: daily 00:25 KST, fallback 01:00 KST. 별도로 남아 있는 Scheduler는
  00:15 KST daily target이다.
- 최신 평가 pointer는 2026-08-26 23:59:56 KST까지다. 최근 daily 실행은
  `ValidationError:date_partition_mismatch`, fallback은
  `RuntimeError:horizon_refresh_requires_exact_completed_corpus`로 종료돼 pointer가 전진하지 않았다.
  이 실패는 번들 자동 갱신과 별개지만 현재 평가 freshness 이상이다.

### Demo EC2 / shadow runtime

- instance: `salmonbus-demo-api` (`i-06dbf1dffb7fb24`), `t4g.small`, running.
- Amazon Linux 2023, SSM `Online`.
- serving current pointer가 가리킨 snapshot은 조사 시각까지 계속 갱신됐다.
- 최신 snapshot에서 관측된 모델 신원은 release `a18-a748cba6ca735e36`, bundle digest
  `652ee361876e2ab38993472ea65fcd182f2737bc7d819c4bfdb8c8c3850f9335` 하나다.
- 이 digest와 같은 bundle이 immutable release artifact
  `c5ff99d/022618...tar.gz`에 있다. artifact version id는
  `8KryPG.KVyrzvAWNvl34tBlREdS_Hruq`, 크기 39,583,681B, last modified
  2026-08-24 04:49:42 KST다.

## 실제 원천 기간·건수·크기

### 운영 전체 inventory snapshot

`processed/source-inventory.json`이 값·객체 키를 출력하지 않고 전수 List한 결과다.

| family | 기간(KST partition) | 객체 수 | 크기 | 마지막 LastModified |
|---|---|---:|---:|---|
| `records/` | 2026-08-14~2026-09-02 | 145,660 | 1,776,593,699B | 2026-09-02T02:57:57Z |
| `raw/` | 2026-08-14~2026-09-02 | 145,535 | 487,419,569B | 2026-09-02T02:58:17Z |

inventory 진행 중에도 collector가 썼으므로 두 family의 마지막 시각이 조금 다르다. 이 숫자는
transactional snapshot이 아니라 명시된 조회 시각의 read-only 관측이다.

완료일별 기대값은 두 노선 합 8,556 object다. 8/24~9/1의 record는 하루
8,555~8,557개로 계속 생성됐고, raw는 8,525~8,556개였다. raw 부족은 호출 실패도 record로
남기는 설계와 일치하며 “수집기 전체 중단”은 아니다. 다만 8/29~8/31 raw의 기대 8,556 대비
부족이 각각 13·31·14개였고, 같은 날 record/raw 차이는 14·30·15개였다. 이 구간은 downstream에서
raw-less failure로 분리해야 한다.
9/2 수치는 조회 중인 부분일이라 완결성 비교에서 제외했다.

### 실제 운영 번들의 동결 입력

| 항목 | 값 |
|---|---:|
| KST dates | 2026-08-14~2026-08-23 (10일) |
| record documents | 65,152 |
| raw documents | 65,095 |
| raw-less failed records | 57 |
| normalized observations | 992,866 |
| passage point events | 114,945 |
| finalized route×horizon examples | 1,236,608 |
| station mismatch records/observations excluded | 4 / 4 |
| combined source manifest SHA-256 | `3e1628f0240515db38c73f04dac6346596a28a663bac7d55e0ef84af25e15536` |
| data through | `2026-08-23T14:59:56Z` |

이 장부는 실제 bundle manifest와 `raw/aggregate-build-receipt.json` 양쪽에 독립적으로 있다.

## 다운로드한 파일

원격에서 내려받아 보존한 것은 필요한 최소 결과물 두 개뿐이다.

| 파일 | 크기 | SHA-256 |
|---|---:|---|
| `bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36/manifest.json` | 61,681B | `2dce5eb26299ebe2ccbe02f02cdc97a7aa9b07ab319690f383b0928fd8b40405` |
| 같은 디렉터리 `weights.safetensors` | 235,752B | `680ef1823971d4a7ba102fd3b04259cc679b676502a0e6e425115fb33fd97be4` |

둘을 이 순서로 이어 잰 SHA-256은 운영 snapshot의 bundle digest와 같은 `652ee3…f9335`다.

## 왜 row-level raw를 넣지 않았나

schema-only 표본 검사에서 private record에 다음 필드가 확인됐다.

- 원본: `buses[].vehId`, `buses[].plateNo`, 동일 값이 든 `response_envelope`
- 가명: `buses[].pseudonyms.vehId_hmac`, `plateNo_hmac`
- raw body: `vehId`, `plateNo`

한 객체만으로도 민감 경계를 넘고, 한 객체는 10일 적합을 재현하기에 충분하지도 않다. 정식 adapter는
이 값을 메모리에서만 결합하고 숫자 bundle과 aggregate receipt만 쓴다. 따라서 `raw/`에는 안전한
aggregate receipt와 정책 설명만 둔다.

## 재현 명령

자격증명·키는 명령에 넣지 않는다. profile은 기존 read-only-capable profile을 쓴다.

```bash
export AWS_PROFILE=<READ_ONLY_PROFILE>
export AWS_REGION=ap-northeast-2

scripts/download-production-bundle.sh <NEW_OUTPUT_DIRECTORY>

A18_PYTHON_BIN=<PYTHON_3_11> \
scripts/rebuild-production-bundle.sh \
  /path/to/salmonbus-analysis \
  <NEW_REBUILD_OUTPUT_DIRECTORY>

scripts/validate-bundle.py <BUNDLE_DIRECTORY>
scripts/validate-production-runtime.sh /path/to/salmonbus-analysis <BUNDLE_DIRECTORY>
```

rebuild는 S3 List/Get만 사용하고 row-level intermediate를 쓰지 않는다. output은 존재하지 않는 새
경로여야 한다.

## 재현 실행 결과

2026-09-02에 Python 3.11.15, producer commit `c5ff99d`, 고정 source manifest로 전체 adapter를
재실행했다.

- status: succeeded
- duration: 869.222초
- peak RSS: 1,512.859MiB
- record/raw/point/finalized: 65,152 / 65,095 / 114,945 / 1,236,608
- source manifest: `3e1628…15536`
- rebuilt release/bundle: `a18-a748…` / `652ee3…f9335`
- rebuilt `manifest.json`: 운영 파일과 byte-identical
- rebuilt `weights.safetensors`: 운영 파일과 byte-identical

첫 두 시도는 macOS `/var`→`/private/var` alias가 producer의 source digest path 계산에 걸려
fail-closed됐다. upstream producer source나 계산은 고치지 않고, wrapper가 temporary extraction
directory만 `pwd -P`로 정규화했다. 그 뒤 adapter/evaluator implementation digest도 manifest의
`aefd2e…f0dfdd` / `baa3b8…4c93`과 일치했다. 안전한 진단은
`processed/rebuild-diagnostics.json`, aggregate receipt와 byte 비교는
`processed/rebuild-receipt.json`·`processed/rebuild-verification.json`에 있다.
