# 노선 형상 API 프론트엔드 인계

이 PR은 **백엔드·공개 API와 이 구현 가이드**를 전달합니다. 현재 로컬 웹/iOS는 아래 표현을 구현한 참고본이며 **프론트·iOS 제품 코드는 이 백엔드 PR에 포함되지 않습니다.** 프론트 팀은 마지막 체크리스트에 따라 별도 구현해야 합니다.

비교 기준은 `origin/dev`의 `09f23452e5553f7903667714ccc7f4cbf615a3c2`입니다. 해당 커밋의 DTO·OpenAPI를 `git show`로 읽어 현재 작업 내용과 비교했습니다. 기준 브랜치에도 이미 `GET /api/v2/routes/{routeId}/geometry`가 있습니다. 이번 변경을 v1→v2 신규 엔드포인트 전환으로 이해하면 안 됩니다.

공식 wire 계약: [route-geometry-v2.openapi.json](../contract/route-geometry-v2.openapi.json). 좌표·상태·구간 예시는 현재 로컬 보관 원문 재생 환경에서 실제 HTTP로 캡처했습니다. 현재 운영 서버의 배포·최신 도로 정확도·iOS 실기동을 입증하는 예시는 아닙니다.

현재 실제 로컬 API는 `automatic-quality-v5`입니다. 같은41노선 재생에서40개는 `REVIEW_REQUIRED`,1개는 `PASSED`이며, v4에서 보류됐던5개도 완전한 source-occurrence 근거와 최종 구조 검사를 통과했습니다. 원본 좌표와 물리 정확도 등급은 바뀌지 않았습니다. 이41개 로컬 지도와 웹/iOS 표현은 참고 검증이며 백엔드 PR의 프론트 제품 코드나 운영 반영 범위가 아닙니다.

## 1. 공개 JSON 변경과 호환성

기존 원본 필드와 배열 순서·null 의미를 유지하고 표시 자료를 추가합니다. 원본 `points`, `paths`, `busStops[].point`를 새 표시 자료로 덮어쓰지 않습니다.

```diff
 {
   "points": [원본 형상점],
   "paths": {기존 방향별 원본 경로},
   "busStops": [{
     "stationId": "...", "stopSequence": 1, "direction": "UP",
     "boardingAllowed": true, "point": {"x": 원본경도, "y": 원본위도},
+    "displayPoint": null 또는 검수 표시점,
+    "pathConnection": {"point": {...}, "segmentIndex": 0, "segmentFraction": 0.5}
   }],
+  "displayPaths": {"UP": {"points": [...]}, "DOWN": {"points": [...]}} 또는 null,
+  "displaySource": "MANUAL_REVIEWED" 또는 "AUTOMATIC_FALLBACK" 또는 null,
+  "displayEvidence": [{"id": "...", "status": "...", "digest": "..." 또는 null}],
+  "displayQuality": {"status": "...", "issues": [...] } 또는 null,
+  "displayAccuracyGrade": "UNEVALUATED" 또는 null
 }
```

위 diff는 **필드 구조 설명**이며 과거 실제 HTTP나 실행용 JSON fixture가 아닙니다. v5는 위의7개 추가 필드 구조를 더 바꾸지 않았습니다. 내부 source witness 판정은 서버 구현이며 새 `witness` boolean이나 WEB 역할/원본 인덱스 메타데이터를 공개 JSON에 추가하지 않습니다.

| 필드 | 필수/선택·null | 의미와 소비 규칙 |
|---|---|---|
| `routeId`, `referenceVersionId` | 기존 필수 문자열 | ID와 64비트 판본은 문자열 유지. `Number(referenceVersionId)`로 바꾸지 않음 |
| `geometryRevision` | 기존 필수 64자리 hex 문자열 | 불투명 캐시 판본. 표시 선택·알고리즘·근거가 바뀌어도 갱신되므로 ref가 같다고 무시하지 않음 |
| `status`, `reason` | 기존 필수 키, reason nullable | 원본 자료의 `READY/PARTIAL/UNAVAILABLE`, 아래 표시 보류와 구분 |
| `coordinateSystem` | 기존 필수 `WGS84_ASSUMED` | x=경도/y=위도로 해석한다는 가정. 측량·좌표계 확인 완료를 뜻하지 않음 |
| `busStops`, `points` | 기존 필수 배열 | 방문 목록과 도로 형상은 별개. 정류장 좌표를 직선으로 이어 노선을 만들지 않음 |
| `busStops[].point` | 기존 필수 키, nullable | 제공된 원본 정류장 위치. null이면 기호 생략, 선 위 점으로 임의 복원하지 않음 |
| `paths`, `boundary`, `turningPoint` | 기존 필수 키, nullable | UP/DOWN은 각각 실제 운행 순서. DOWN을 UP 역순으로 생성하지 않음 |
| `displayPaths` | **신규 선택 키**, nullable | 현재 원본에 결박된 선택 표시 경로. UP/DOWN을 함께 사용 |
| `displaySource` | **신규 선택 키**, nullable | 서버가 수동 우선으로 선택한 `MANUAL_REVIEWED/AUTOMATIC_FALLBACK`. 클라이언트가 상태명을 바꿔 승인하지 않음 |
| `displayEvidence` | **신규 선택 배열**, 현재 서버는 배열 제공 | id/status/digest(null 가능). 상태는 열린 문자열이며 `AVAILABLE_WITH_QUARANTINE` 같은 부분 가용 상태도 가능 |
| `displayQuality` | **신규 선택 키**, nullable | `PASSED/REVIEW_REQUIRED/SOURCE_ONLY/WITHHELD` 및 검사 사유 |
| `displayAccuracyGrade` | **신규 선택 키**, nullable | 현재 표시 자료는 `UNEVALUATED`, 표시 자료가 없으면 null. `ROAD_VERIFIED/LANE_VERIFIED`는 예약 값이며 현재 발급하지 않음 |
| `busStops[].displayPoint` | **신규 선택 키**, nullable | 검수 표시점. 원본 GPS나 독립 승차 위치 truth로 재해석하지 않음. 현재 자동 결과는 null 가능 |
| `busStops[].pathConnection` | **신규 선택 키**, nullable | 선택된 해당 방향 경로의 동일 방문 연결점. point/index/fraction 세 필드는 객체가 있으면 모두 필수 |

기존 required 목록은 그대로입니다. 새 필드는 원본 응답을 읽는 코드에 대해 additive지만, **기존 `additionalProperties:false` 스키마로 런타임 검증하면 새 키를 거절할 수 있으므로 스키마/클라이언트를 갱신해야 합니다.** 새 필드가 없는 구 응답은 기존 원본 표시로 처리합니다. 신규 `WITHHELD`를 모르는 구 클라이언트는 원본선으로 잘못 우회할 수 있으므로 표현 기능 활성화 전에 프론트 호환 배포를 맞춰야 합니다.

## 2. 방문 identity와 선택 묶음

방문은 `(routeId, referenceVersionId, direction, stopSequence)`로 구별하고 `stationId`도 원본 그대로 보존합니다. 같은 stationId가 여러 번 나타날 수 있으므로 ID나 이름으로 합치지 않습니다. `turningPoint.stopSequence`는 방문 순번이며 형상 배열 인덱스가 아닙니다.

표시 자료를 쓰기 전에 **경로·모든 방문 연결·표시 자료를 하나의 판본으로 검증**합니다.

1. route/ref/coordinateSystem이 현재 보드와 일치해야 합니다.
2. 표시 UP/DOWN 둘 다 유효한 배열이어야 합니다. 유한 좌표·범위·서로 다른 두 점 이상을 검사합니다.
3. 원본 point가 있는 각 방문은 자기 방향 배열의 연결을 가져야 합니다. `segmentIndex`는 0기반이며 끝점+1이 존재해야 하고 fraction은 유한한 0..1입니다.
4. `connection.point = p[i] + fraction*(p[i+1]-p[i])`가 각 축 `1e-9` 이내로 일치하고 방향별 진행 순서가 역행하지 않아야 합니다. 인덱스는 **displayPaths** 기준이지 원본 points 기준이 아닙니다.
5. 필수 연결이 없거나 잘못됐으면 표시 묶음 전체를 기존 원본 정책으로 되돌립니다. 일부 새 선과 옛 연결점을 섞거나 GPS를 nearest로 다시 투영하지 않습니다.
6. 단, 명시적인 `AUTOMATIC_FALLBACK + WITHHELD`는 **원본선으로 우회하지 않습니다.** 표시선·차량 보간선을 숨기고 제공된 정류장 위치만 표시할 수 있습니다.

`PASSED`는 구조 검사 통과이며 도로/차로 검증 등급이 아닙니다. `REVIEW_REQUIRED`는 좌표 차이 같은 사유가 남아도 검증된 선과 연결을 쓸 수 있는 상태입니다. `SOURCE_ONLY`는 검증된 원본선 대체 결과이고 정확도 승격이 아닙니다. `displayEvidence`의 공급자 상태와 `displayQuality`의 표시 허용 여부를 혼동하지 않습니다.

### v5의 큰 제공 위치 간격과 표시 허용

제공 GPS와 노선 연결점의 간격이30m를 넘을 때도, 서버는 **원문에 결박된 완전한 indexed TYPE1 방문 순서, 유일한 동일 occurrence, 방향·회차·원래 전체 방문 window**를 확인하고 실제 최종 연결점이 그 window 안에 보존됐을 때만 대표 운행선 표시를 허용합니다. 곡선을 만들었다면 최종 serialized connection을 같은 source window로 역대응하는 검사도 통과해야 합니다. 기존100m hard cap과 모든 연결·방문 구간·끝점·교차·곡률 검사는 유지합니다.

이는 “큰 간격이면 무조건 허용”하거나 물리적 승강장 위치가 확인됐다는 뜻이 아닙니다. `STOP_CONNECTION_GAP` 경고는 남고 `REVIEW_REQUIRED/UNEVALUATED`로 전달됩니다. 물리적 승차 위치·차로 정확도는 여전히 미확정(UNKNOWN)이며, 이는 별도의 공개 JSON 필드나 검증 등급이 아닙니다. 유한한 WEB 근거 deadline(`validUntil`)은 현재 캐시 수명과 결박되지 않아 이 조건부 허용의 witness로 사용하지 않습니다. 근거 누락·불완전·모호한 대응, window 이탈,100m 초과나 구조 실패는 계속 보류할 수 있습니다.

프론트는 새30m 허용 규칙이나 원본 WEB TYPE1 검증을 재구현하지 않습니다. 서버가 선택한 상태와 같은 판본의 전체 표시 묶음을 위 규칙대로 소비하고, GPS gap 숫자만으로 자체 승인·보류를 재분류하지 않습니다. 현재41개에 보류가 없다는 실측과 미래의 `WITHHELD` 계약은 별개입니다.


## 3. 실제 응답 예시

다음 JSON은 실제 응답에서 **필드와 배열 원소를 발췌**했습니다. 생략된 필수 필드와 전체 배열이 있어 실행용 fixture가 아닙니다. 전체 캡처 URL·파일 SHA는 문서 끝의 근거 목록을 참고합니다.

### 3330: 원본과 노선 연결점이 다른 방문

`GET /api/v2/routes/204000057/geometry` → 200. 원본 836점/85방문, 원본 방향 배열 UP416/DOWN422점, 선택 표시 배열 UP5870/DOWN5870점입니다. `displayQuality.status=REVIEW_REQUIRED`이고 원본 위치 차이를 지우지 않았습니다.

```json
{
  "routeId": "204000057",
  "referenceVersionId": "2",
  "geometryRevision": "ef6542c791900e2d750f317e386b0019416ce8696deafb4af1a44b934016bdc8",
  "coordinateSystem": "WGS84_ASSUMED",
  "displaySource": "AUTOMATIC_FALLBACK",
  "displayAccuracyGrade": "UNEVALUATED",
  "busStops": [
    {
      "stationId": "205000227",
      "stopSequence": 1,
      "name": "도촌동9단지앞",
      "direction": "UP",
      "boardingAllowed": true,
      "point": {
        "x": 127.1627833,
        "y": 37.4118167
      },
      "displayPoint": null,
      "pathConnection": {
        "point": {
          "x": 127.16282659556956,
          "y": 37.41179242001574
        },
        "segmentIndex": 1,
        "segmentFraction": 0.0
      }
    }
  ]
}
```

이 방문의 point와 pathConnection.point는 다릅니다. 제공 정류장 위치는 전자, 노선 대응점/차량 보간은 후자입니다. `segmentIndex=1`은 선택 UP 배열의 1→2 선분을 뜻합니다. 자동 결과에서 displayPoint=null이라고 해서 pathConnection을 버리면 안 됩니다.

### 1009: 현재 v5는 큰 간격 경고가 남은 REVIEW_REQUIRED

`GET /api/v2/routes/234000310/geometry` → 200. 원본1303점/143방문과 원본 방향 배열 UP644/DOWN659점은 유지됩니다. 현재 선택 표시 배열은 **UP8015/DOWN9926점**,143개 방문 연결이 있으며 `displayQuality.status=REVIEW_REQUIRED`입니다. 다음은 실제 응답에서72번 방문과 해당 사유만 발췌한 예시입니다.

```json
{
  "routeId": "234000310",
  "referenceVersionId": "12",
  "geometryRevision": "9a9af5ae3314d009350f3974d14b488e1d13302c81a1da2138144d38db9455da",
  "status": "READY",
  "reason": null,
  "displaySource": "AUTOMATIC_FALLBACK",
  "displayAccuracyGrade": "UNEVALUATED",
  "busStops": [
    {
      "stationId": "123000611",
      "stopSequence": 72,
      "name": "잠실광역환승센터",
      "direction": "UP",
      "boardingAllowed": true,
      "point": {
        "x": 127.1006667,
        "y": 37.51235
      },
      "displayPoint": null,
      "pathConnection": {
        "point": {
          "x": 127.101343701,
          "y": 37.5116712
        },
        "segmentIndex": 8013,
        "segmentFraction": 0.9999999999959696
      }
    }
  ],
  "displayQuality": {
    "status": "REVIEW_REQUIRED",
    "issues": [
      {
        "code": "STOP_CONNECTION_GAP",
        "direction": "UP",
        "sequence": 72,
        "meters": 96.352
      }
    ]
  }
}
```

96.352m의 원본 위치 차이가 삭제되거나 GPS가 노선으로 이동한 것이 아닙니다. 서버가 원문 방문 window와 최종 연결·구간 구조를 증명해 대표 운행선과 같은 방문 연결을 제공한 상태입니다. 이 연결은 실제 탑승 위치·지하 이동 경로·차로 정확도 인증이 아닙니다. 프론트는 경고를 유지하면서 선택된 표시선과143개 연결을 같은 묶음으로 사용합니다.

#### 변경 전 v4 응답: WITHHELD 계약 참고

아래는 **변경 전 실제 v4 캡처**입니다. 현재1009의 상태가 아닙니다. 당시에도 원본은 `READY`였지만 큰 간격에 대한 추가 source witness가 없어 표시선·연결을 보류했습니다.

```json
{
  "routeId": "234000310",
  "referenceVersionId": "12",
  "geometryRevision": "54ccc747598bed5d09897fcab7448592a4fac43509d909aec5c263b7094629fd",
  "status": "READY",
  "reason": null,
  "displaySource": "AUTOMATIC_FALLBACK",
  "displayPaths": null,
  "displayAccuracyGrade": "UNEVALUATED",
  "displayQuality": {
    "status": "WITHHELD",
    "issues": [
      {
        "code": "STOP_CONNECTION_GAP",
        "direction": "UP",
        "sequence": 72,
        "meters": 96.352
      }
    ]
  }
}
```

이전 응답도404·503·UNAVAILABLE은 아니었습니다. 현재처럼 증명이 없는 입력이나 모호한 반복 방문·window 이탈·100m 초과·구조 실패에서는 같은 `WITHHELD` 계약이 계속 적용됩니다. 명시적 보류를 원본선 표시로 우회하지 않습니다.

## 4. 프론트 내부 모델: 서버 JSON에 없는 필드

아래는 **로컬 참고 구현의 FE/native scene 모델**이며 공개 geometry API에 추가된 필드가 아닙니다.

```ts
interface RouteMapStop {
  stopId: string;
  sequence: number;
  direction: "UP" | "DOWN";
  coordinate: { latitude: number; longitude: number };
  sourceCoordinate?: { latitude: number; longitude: number };
  connectionCoordinate?: { latitude: number; longitude: number };
  endpointRole?: "DEPARTURE" | "ARRIVAL";
  pathConnection?: { segmentIndex: number; segmentFraction: number };
}
```

- `sourceCoordinate = { latitude: stop.point.y, longitude: stop.point.x }`로 원본을 보존합니다.
- **승차 정류장 coordinate는 제공 point**를 사용합니다. 원본 GPS가 노선과 떨어졌다는 이유로 물리적 정류장 위치를 옮기지 않습니다.
- 유효한 선택 묶음이 있으면 `connectionCoordinate`는 해당 `pathConnection.point`입니다. **비승차 경유 기호 coordinate는 이 foot**를 사용하고, 없는 선에 강제 스냅하지 않습니다.
- 승차 위치와 연결점의 화면 거리가 약8px를 넘으면 얇은 중립 대응선으로 관계만 표시합니다. 현재 참고 구현은 웹2.5 CSSpx/iOS2.5 UIKitpt 점선이며, **버스 운행선·도보 경로·도로 정확도 개선을 뜻하지 않습니다.** 가까운 대응점은 합치거나 생략합니다.
- `endpointRole`은 API 플래그가 아닙니다. 현재 참고본은 전체 방문 중 첫 UP 승차/마지막 DOWN 승차에서 도출하고, Board.route.directions의 terminalStopName으로 “출발 · … 방면/도착”을 만듭니다. stationId/노선명/모바일 번호를 하드코딩하지 않습니다. API에 모바일 정류장 번호는 없습니다.
- 지도 기호·선택 테두리·차량의 실제 anchor는 고정하고 이름표·좌석 배지·독립 클릭 영역만 화면 충돌을 피합니다.
- 차량 위치는 geometry나 watch의 독립 GPS가 아닙니다. 현재 watch Vehicle에는 direction/currentStopSequence/phase/seat 등이 있으며 위도·경도는 없습니다. 웹과 native는 검증된 pathConnection 진행도에서 이동을 보간합니다.
- 수동 우선은 서버가 선택한 manual path/connection을 사용한다는 뜻입니다. 공개 displayPoint를 삭제하거나 재발행하지 않으며, 현재 로컬 표현은 승차 위치와 노선 대응점을 위 규칙대로 구분합니다.

참고 경로는 `frontend/src/pages/verdict-board/map/{routeGeometryMapData,routeMapModel,routeMapGeometry,nativeMapAdapter}.ts`, `frontend/src/shared/native-map/protocol.ts`, `app/modules/salmon-native-map/ios/MapContract.swift`입니다. 이 파일들의 **현재 로컬 변경은 백엔드 PR 포함분이 아닙니다.** iOS는 계약·순수 배치·구문 검사를 했지만 실기기/시뮬레이터 실행과 전체 SDK 빌드는 미검증입니다.

## 5. 조회·캐시·오류

```http
GET /api/v2/routes/204000057/geometry?referenceVersionId=2
Accept: application/json

HTTP/1.1 200
ETag: W/"g-ef6542c791900e2d750f317e386b0019416ce8696deafb4af1a44b934016bdc8"
Cache-Control: max-age=300, must-revalidate, public
```

- 노선/보드 판본이 정해질 때 조회합니다. 방향 변경·지도 pan/zoom마다 다시 받지 않습니다.
- referenceVersionId가 같아도 표시 자료·근거·알고리즘 변경으로 geometryRevision/ETag가 바뀔 수 있습니다. geometryRevision이 바뀌면 지도 경로·방문 연결·native scene을 함께 교체합니다.
- `If-None-Match`에 위 ETag를 보내면 실제 서버는 **304/본문0바이트**를 반환했습니다. 직접 ETag를 관리하면 캐시 JSON을 재사용하며 304 본문을 JSON.parse하지 않습니다.
- 로컬 `fetchRouteGeometry`는 `cache:"no-cache"`로 브라우저 HTTP 재검증을 사용하고 프론트 polling lifetime을 최대60초로 제한합니다. 자체 304-body 저장기를 구현한 것은 아닙니다. 별도 클라이언트/프록시는 동일 캐시 의미를 구현해야 합니다.
- READY/PARTIAL(표시 WITHHELD가 발생한 경우 포함)은 최대5분 캐시/ETag 대상입니다. `UNAVAILABLE/NOT_COLLECTED`는 200이지만 points=[]/paths=null, no-store/ETag 없음입니다. 현재 샘플 두 개는 미수집 예시가 아닙니다.
- 오류는 no-store입니다. 400 `INVALID_ROUTE_ID`/잘못된 판본, 404 `ROUTE_NOT_FOUND`, 409 `REFERENCE_VERSION_CONFLICT`, 503 저장소/저장 형상 조회 불가를 구분합니다. 409에서는 구 판본의 방문 인덱스를 버리고 보드·geometry를 현재 판본으로 재조회합니다. 판본 없는 geometry 요청으로 현재 값을 확인할 수 있습니다.
- `/api/v1/routes/204000057/geometry`는 실제 **404 ENDPOINT_NOT_FOUND**이며 alias가 아닙니다. `/api/v1/routes` 목록과 `/api/v1/routes/{id}/watch`는 별도 기존 API이므로 일괄 v2로 바꾸지 않습니다.
- GET은 외부 수집을 시작하지 않습니다. worker 생성이 아직 끝나지 않은 경우 quality issue `GENERATION_PENDING`이 올 수 있습니다. geometry의 READY와 watch/모델/차량 관측 준비 상태는 독립입니다.

## 6. 프론트 팀 구현 요청

- [ ] 신규 선택/null 필드를 수용하도록 타입·runtime schema/생성 클라이언트를 갱신한다. 구 strict schema의 additionalProperties:false에 유의한다.
- [ ] route/ref/geometryRevision 및 direction+sequence로 현재 묶음을 결박하고 반복 stationId/동명 정류장을 합치지 않는다.
- [ ] 표시 경로와 모든 방문 connection을 원자적으로 검증하고 source/manual/automatic 배열을 섞지 않는다. index/fraction/보간/순서를 검사한다.
- [ ] WITHHELD는 원본선 우회 없이 숨기고, REVIEW_REQUIRED/UNEVALUATED·원본 GPS gap을 승격하거나 삭제하지 않는다. v5의30m 초과 조건부 허용은 서버가 검증하며 FE가 gap 숫자나 새 witness 플래그로 재판정하지 않는다.
- [ ] 제공 승차 위치, 원본 메타, 노선 연결점을 별도 모델로 만들고 선택된 occurrence를 nearest로 재추정하지 않는다.
- [ ] 비승차 경유점·좌표 누락·방향 경로 없음은 명시적으로 처리한다. 연결 없는 정류장 직선으로 길을 만들지 않는다.
- [ ] 실제 출발/도착·방면 원천으로 라벨을 만들고 기호·차량 anchor를 고정한다. 중립 대응선은 운행/보행 경로와 구별한다.
- [ ] 선택·차량 보간·좌석 배지·native 메시지는 동일 route/ref/geometry/direction/sequence를 보존한다. source/connection/endpoint 필드는 내부 scene 필드임을 지킨다.
- [ ] 304 캐시 재사용, 409 판본 교체, 404와200미수집,503재시도/no-store를 각각 검증한다. 로컬 hook의 특수409복구가 완결돼 있다고 가정하지 않는다.
- [ ] 원본/선택 배열 수가 다른3330, 현재96.352m 경고와 REVIEW_REQUIRED/143연결을 함께 제공하는1009, 변경 전/증명 불가 WITHHELD, 반복 방문·같은 좌표의 다른 lap·null 좌표·manual 우선 시나리오를 unit/통합 테스트한다.
- [ ] backend 표현 flag를 켜기 전에 신·구 FE 호환을 맞춘다. 로컬 참고 구현과 운영 프론트 반영 여부를 따로 확인한다.
- [ ] iOS 실 SDK 빌드·기기/시뮬레이터 터치·카메라·레이아웃을 별도로 검증한다. 현재 Swift parse/배치/DTO 검사를 실기동으로 세지 않는다.

## 7. 예시 근거

기준 ref: `origin/dev` → `09f23452e5553f7903667714ccc7f4cbf615a3c2`. 공개 구조 비교는 이 커밋의 `contract/route-geometry-v2.openapi.json`과 현재 DTO/계약에 근거합니다. PR 템플릿은 일반 작업 내용/로컬·회귀 확인/참고 사항이며 별도의 API 전용 추가 지침은 없었습니다.

현재 v5의 전체 응답·헤더·304는 연구 산출물 `pr-preparation-20261103/api/v5-final/`에 저장했고, 변경 전 v4 캡처와 오류 검증은 상위 `api/`에 보존했습니다. 큰 payload는 이 PR 문서에 넣지 않았습니다. 아래 SHA는 생략 없는 HTTP 본문 기준입니다. localhost URL은 로컬 재생 출처이며 운영 URL이 아닙니다.

| URL | 전체 파일 | SHA-256 |
|---|---|---|
| `http://127.0.0.1:8080/api/v2/routes/204000057/geometry` | `v5-final/204000057.json` | 736efbeeda0bad38426ddbaa1090923fd25bacff54ca323698ba4ec199b35b80 |
| `http://127.0.0.1:8080/api/v2/routes/234000310/geometry` | `v5-final/234000310.json` | 235188ac65749fe37b78e9b0f8c79b538c7751e5ca1f9f6f1bce54180de2ef6d |

현재 두 응답의8080/3000 본문은 바이트 단위로 같고, 각 새ETag의304/본문0바이트를 `v5-final/summary.json`과 헤더·`.body` 파일에서 확인했습니다. v1경로404·없는노선404·잘못된ID400·판본409의 기존 실제 본문/헤더는 상위 `http-probes.json`과 `.body` 파일에 보존했고 v5가 이 오류 계약을 바꾸지 않습니다. 샘플은 인증키/쿠키 없이 공개 읽기 경로에서 취득했고 source 원문/DB/서버 설정을 바꾸지 않았습니다. 클라이언트 구현은 로컬 숫자·노선 수·고정 revision에 의존하지 않아야 합니다.
