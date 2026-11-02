# SAL-338 노선 형상 자동 생성과 표시 계약

GBIS 원본 선과 정류장 방문을 보존하고, 원문 역할 근거로 만든 표시 형상·방문 연결·품질 상태를 별도 판본으로 제공합니다. 현재 제품 생성기는 `automatic-quality-v5`입니다. 이번 PR은 백엔드·공개 API·이 구현 문서를 포함하며, 로컬 웹/iOS 참고 구현은 포함하지 않습니다. 지도 연동은 [프론트 구현 가이드](route-geometry-frontend-handoff.md)를 따릅니다.

## 원본과 표시 자료

- GBIS 인증 API에서 노선 정보, 방문 순서·회차·정류장 위치, 형상점 순서를 각각 읽습니다. 정류장 목록을 이어 도로를 만들지 않습니다.
- GBIS 공개 웹의 TYPE1/TYPE2/TYPE3와 원본 배열의 순서·좌표 대응을 검사합니다. 두 응답은 같은 제공자의 다른 전송 경로이며 독립 측량 정답은 아닙니다.
- 경기 BMS 노드의 양의 근접 근거를 함께 씁니다. 잘못된 좌표는 격리하고, 노드의 부재만으로 길이나 삭제를 허가하지 않습니다.
- 수집 결과는 `AVAILABLE`, 부분 가용, 격리, 실패·만료 등 상태와 원문 receipt/SHA로 추적합니다. 인증키·인증 URL은 원문 보관 대상에서 제외합니다. 원문 없는 과거 자료에 출처를 소급 생성하지 않습니다.
- `points`, `paths`, `busStops[].point`는 원본입니다. 표시 경로는 `displayPaths`, 해당 방향·방문 연결은 `pathConnection`으로 분리합니다.

## 자동 생성 순서

1. 원본의 방문 순서로 회차 경계를 찾고 원본 방향별 경로를 검증합니다. 같은 stationId의 반복 방문을 합치지 않습니다.
2. 원문 역할이 확인된 연결 노드를 검사합니다. TYPE2의 반복·양방향 출현과 BMS 근접 근거를 보수적으로 사용하며 TYPE3·unknown·방문·끝점은 임의 삭제하지 않습니다. 순차 정리로 독립 검증한 전체 경로를 되돌아갈 기준으로 보존합니다.
3. 일반 구간에 원문 범위 안의 quadratic 곡선을 만들고, TYPE1·방문 근처에서는 실제 원문 위치를 통과하는 cubic을 사용합니다. 파생 끝점도 원본 선분의 occurrence/fraction을 증명한 경우에만 사용합니다.
4. 곡선은 **연결 노드 정리 후 기준 경로의 미터 호길이**에 대응시킵니다. control hull와 실제 renderer chord의 이격을 2m 안에서 검사하고 적응 분할합니다. 이는 원본 노드 정리까지 포함한 모든 변경의 raw 대비 2m 보장이나 실제 도로 정확도 인증이 아닙니다.
5. 최종 직렬화 결과에서 모든 방문의 index/fraction·방향별 순서, 인접 구간, 회차 연결, source occurrence, 끝점, 새 교차·비인접 접촉·overlap·굽힘 악화를 검사합니다. 선택된 최종 배열에서 방문 연결을 다시 검증합니다.
6. anchor 곡선이 실패하면 독립 통과한 일반 곡선 전체 묶음, 그마저 실패하면 검증된 연결 노드 정리 묶음으로 돌아갑니다. 다른 후보의 선과 연결 일부를 섞지 않습니다. 유효한 대체도 없으면 표시를 보류합니다.

공유한 정확히 같은 끝점은 proper crossing이 아니므로 교차 판정에서 제외하며, 비인접 접촉·overlap 검사는 별도로 유지합니다. 1650의 Mercator double 계산에서 끝점 교차가 t=u≈1로 잘못 분류되던 반례와 1µm 차이의 실제 교차 보존을 검사했습니다.

현재 판교 사례의 X는 두 방향이 원문 연결 노드의 공유 좌표를 지나는 데서 발생했습니다. 모든 지도상의 교차를 결함으로 간주하거나 삭제하지 않습니다. 곡선 생성도 원본 GPS·방문 위치를 임의 이동하는 보정이 아닙니다.

## 생성·저장·조회 경계

`route-geometry-contract`는 원문·근거·입력·수동 등록의 순수 계약을, `route-geometry-engine`은 형상·연결·품질 검사를 담당합니다. worker가 수집과 생성을 수행하고 API는 저장된 현재 자료를 읽습니다. GET에서 외부 수집이나 형상 생성을 시작하지 않습니다.

- V48: 원본에 결박된 별도 표시 자료와 검수 자료를 저장합니다.
- V49: 생성 작업을 영속화합니다. 중복 키 합치기, lease·소유권 토큰, 실패 재시도, 늦은 완료 차단을 적용합니다.
- V50: 수집 원문과 생성에 실제 사용한 입력·출처를 내용 SHA 및 source manifest로 결박합니다.
- 생성 키는 원본 exact input, 근거, 알고리즘/구현 판본을 포함합니다. 생성은 DB 잠금 밖에서 수행하고 발행할 때 현재 입력과 소유권을 다시 검사합니다. 원본이 바뀐 뒤 끝난 이전 계산은 현재 자료를 덮어쓰지 않습니다.
- 현재 입력에 맞는 검수 표시 자료를 우선하고, 없으면 현재 자동 산출물, 검증된 원본 산출물 순서로 선택합니다. 생성 대기·무효 자료는 품질 상태로 구별합니다.
- `geometryRevision`과 ETag는 원본 판본이 같아도 선택 표시 자료·알고리즘·근거가 바뀌면 갱신됩니다. API에서 조회할 때 회차나 최근접 정류장 대응을 다시 계산하지 않습니다.

## 품질과 정확도

| 상태 | 표시 의미 |
|---|---|
| `PASSED` | 구조 검사 통과. 실제 도로·차로 정확도나 보정 발생을 뜻하지 않음 |
| `REVIEW_REQUIRED` | 구조 검사를 통과한 경로를 제공하되 정류장 원본 좌표 차이 같은 검토 사유가 남음 |
| `SOURCE_ONLY` | 후보를 사용하지 않고 검증된 원본 묶음으로 대체 |
| `WITHHELD` | 경로·차량 보간선 표시를 보류. 프론트가 원본선을 임의로 다시 그리지 않음 |

`displayAccuracyGrade`는 별개이며 현재는 `UNEVALUATED` 또는 null입니다. `ROAD_VERIFIED`·`LANE_VERIFIED`는 예약 계약으로 현재 발급하지 않습니다.

승차 정류장 이격 15m 초과는 검토 사유로 유지합니다. 30m 초과는 단순 거리 완화로 통과시키지 않고 아래 원문 방문 증명을 모두 요구합니다. 기존 100m 대응 상한, 모호성·방문 구간·끝점·회차·새 접촉 검사는 그대로 차단 조건입니다. 이 값은 표시 정책이며 도로 너비·승차 위치 정확도 기준이 아닙니다.

- source manifest가 결박되고 전체 원문 역할 순서가 대응해야 합니다. 현재 lifecycle에서 만료 판본을 안전하게 바꿀 수 없는 finite WEB deadline은 큰 간격의 증명 근거에서 제외합니다.
- TYPE1과 카탈로그 방문을 순서·방향으로 일대일 대응하고, 원본→후보의 유일한 순서 보존 삭제 대응으로 반복 좌표의 다른 회차를 구분합니다.
- 원본 전역 TYPE1 이웃의 midpoint 방문 구간과 후보 구간의 교집합 안에 실제 연결점이 있어야 합니다. 공유 회차는 두 방향의 전역 이웃을 모두 검사합니다. 긴 회차 선분의 임의 절단으로 방문 구간이 재정의되는 반례를 차단합니다.
- 곡선의 최종 직렬화 index/fraction을 실제 source-s로 되돌려 같은 방문 구간에 있는지 다시 검사합니다. 원래 위치·경고·정확도는 바꾸지 않습니다.

이 증명은 대표 노선에 연결된 동일 방문이라는 근거이며 실제 승강장 위치나 지하 주행 동선의 검증이 아닙니다. [정류장 위치 원인 조사](route-stop-location-analysis.md)를 참고합니다.

같은 41개 보관 입력/3,215방문의 최종 v5 재생에서는 41개 모두 표시 가능하며 40개 REVIEW_REQUIRED·1개 PASSED입니다. 곡선 적용 35개/6,110곡선, 곡선을 거절하고 검증된 노드 정리 결과를 유지한 4개, 원본 구조를 유지한 2개가 포함됩니다. 519개 GPS 이격 경고는 그대로입니다. 이 결과를 실제 도로·차로 정확도나 모든 원본 위치 검수 완료로 확대하지 않습니다.

## 프론트 표현과의 경계

[구현 가이드](route-geometry-frontend-handoff.md)에 공개 JSON 추가 필드, 원자적 묶음 검증, 캐시·오류 처리 및 참고 화면을 정리했습니다.

승차 핀은 제공 GPS, 비승차 경유점은 유효한 연결점에 둡니다. 제공 위치와 노선 대응점은 중립 연결선으로 구분하고 핀·차량 anchor는 고정한 채 이름표·좌석 배지만 화면에서 배치합니다. 로컬 참고본의 대응 점선은 웹 2.5 CSSpx/iOS 2.5 UIKitpt입니다. 이 선은 차량 운행선이나 도보 경로가 아닙니다. 차량은 watch의 독립 GPS가 아니라 방향·방문 순번에 따른 노선상 추정 위치입니다.

`sourceCoordinate`, `connectionCoordinate`, `endpointRole`는 프론트 내부/네이티브 scene 모델이며 공개 API 필드가 아닙니다. 프론트/iOS 제품 코드와 개발용 41노선 검토 화면은 이 백엔드 PR 포함분이 아닙니다.

## 선택적 참조 지도 실험

ITS 표준노드링크·NGII 정밀도로지도·회전 제약을 사용하는 native 참조 실험은 `native-reference-shadow-v1`으로 분리합니다. 서버가 고정한 자료·SHA·모델 입력 집합으로만 실행하고 기본값은 꺼짐입니다. 현재 자동 v4는 이 참조 그래프로 원본 노선을 대체하지 않습니다. shadow 결과는 `published=false`, `modelAdopted=false`, `UNEVALUATED`이며 API의 현재 표시 산출물 선택에 포함하지 않습니다. OSM은 과거 연구 viewer의 바탕 타일로만 사용됐고 v4 보정 원천이 아닙니다.

## 활성화·운영 도구

V48→V50 마이그레이션과 프론트 호환 배포를 먼저 맞춥니다. 실제 수집 대상·주기·호출 예산을 환경에 맞게 설정하며, 형상 수집 대상과 차량 watch 수집 대상은 별개입니다.

| 설정 | 기본값·역할 |
|---|---|
| `ROUTE_GEOMETRY_ENABLED` | false. 원본 형상 수집 |
| `ROUTE_GEOMETRY_MULTI_SOURCE_ENABLED` | false. WEB/BMS 보조 근거 수집 |
| `ROUTE_GEOMETRY_GENERATION_ENABLED` | false. 영속 작업 생성·처리 |
| `ROUTE_GEOMETRY_PRESENTATION_ENABLED` | false. API 표시 자료 선택 |
| `ROUTE_GEOMETRY_SHADOW_ENABLED` | false. 별도 참조 실험, 활성화해도 미발행 |

worker 원본 갱신은 기본 24시간, 보조 근거 cache는 6시간이며 API/프론트 재검증 주기와 다릅니다. 실제 상류 변경 감지 지연을 초 단위로 보장하지 않습니다.

maintenance의 `route-display-export/status/import/disable` 명령은 공개 쓰기 HTTP 없이 검수 자료를 다루는 별도 도구입니다. [수동 등록 schema](../contract/route-display-manual-v1.schema.json)는 현재 입력 digest와 검수 메타, 방문별 연결을 검증합니다. 현재 41개 자동 재생에는 수동 좌표 수정이나 노선별 예외를 적용하지 않았습니다. 원격 import/disable은 기존 운영 승인 규약을 따릅니다.

## 검증과 사진

계약 digest 골든, 역할 대응, final chord/source bound, 방문·회차·구간·새 접촉 검사, fallback 원자성, 큐 lease·재시도·fencing, source manifest, DB/HTTP 수동→자동→해제·캐시와 실행 프로세스 검사를 포함합니다.

```sh
backend/gradlew -p backend --offline \
  :route-geometry-contract:check :route-geometry-engine:check :common:check \
  :api-app:check :maintenance-app:check :worker-app:check :route-catalog:check \
  :runtime-test:check --console=plain
```

[같은 입력·카메라의 실제 앱 전후 캡처 근거](screenshots/SAL-338/capture-manifest.json)를 보존했습니다. before는 기준 `origin/dev` 프론트를 격리 실행한 재현이고, after는 표시 API를 소비하는 로컬 프론트 참고 구현입니다. 두 화면의 geometry/watch 응답과 원본 자료는 동일하며, 각 클라이언트가 선택하는 원본/표시 배열이 다릅니다. 과거 운영 현장의 촬영이나 실제 iOS 실행 검증으로 제시하지 않습니다.
