# 노선 형상 API 프론트 구현 가이드

`GET /api/v2/routes/{routeId}/geometry`의 기존 필수 필드와 조회 방식은 유지합니다. 서버가 최종 노선을 선택하므로 프론트는 기존 `paths`를 그대로 그리면 됩니다. 이번 PR에는 백엔드·API·가이드를 담고, 아래 지도 표현은 프론트에서 반영합니다.

## API 변경

```diff
 {
   "points": [원본 전체 형상],
-  "paths": {기존 방향별 원본 경로},
+  "paths": {서버가 선택한 최종 방향별 경로},
   "busStops": [{
     "stationId": "...", "stopSequence": 1, "direction": "UP",
     "boardingAllowed": true, "point": {"x": 127.1627833, "y": 37.4118167},
+    "pathConnection": {
+      "point": {"x": 127.16282659556956, "y": 37.41179242001574},
+      "segmentIndex": 1, "segmentFraction": 0.0
+    }
   }]
 }
```

배열을 축약한 구조 예시입니다. 기존 14개 최상위 필드를 유지하고, 정류장에 선택 필드 `pathConnection` 하나를 추가합니다. 원본 정류장 `point`, 방문 순서, 방향과 반복 방문은 그대로입니다. 공개 스키마는 [OpenAPI](../contract/route-geometry-v2.openapi.json)를 참고합니다.

| 필드 | 사용하는 방법 |
|---|---|
| `paths.UP/DOWN.points` | 서버가 선택한 최종 노선. 프론트에서 원본/개선 후보를 다시 선택하지 않습니다. |
| `points` | 기존 전체 원본 형상. `paths=null`인 기존 단일 경로 표현에 사용합니다. |
| `busStops[].point` | 제공된 정류장 위치. `null`이면 해당 위치 기호를 생략합니다. |
| `busStops[].pathConnection` | 같은 방문의 `paths`상 연결점. 없는 구 응답도 수용합니다. |
| `geometryRevision` | 선·연결점 묶음의 캐시 판본. 변경되면 함께 교체합니다. |

생성 가능한 개선 노선은 품질 경고가 있어도 우선 사용합니다. 생성할 수 없으면 검증된 기존 방식으로 대체하며, 양쪽 모두 사용할 수 없으면 `status=UNAVAILABLE`, `reason=DISPLAY_WITHHELD`, `points=[]`, `paths=null`을 반환합니다. 이 경우 정류장은 원본 위치에 표시할 수 있습니다.

## 프론트 변경점

1. 기존 `paths` 렌더링을 유지하고 `pathConnection` 선택 타입을 추가합니다.
2. 승차 정류장 핀은 `point`에 둡니다. 노선과 떨어져 있으면 `pathConnection.point`까지 중립 점선으로 연결합니다. 로컬 참고 화면은 2.5px 점선을 사용합니다.
3. 비승차 경유 기호는 연결점이 있으면 `pathConnection.point`에 둡니다.
4. 차량의 정류장 간 이동에는 연결점의 선분 인덱스와 비율을 우선 사용합니다. 같은 좌표를 여러 번 지나는 노선에서도 방문 순서를 유지할 수 있습니다.
5. 정류장은 `(direction, stopSequence)`로 구분합니다. 같은 stationId·이름의 출발/도착 방문을 합치지 않습니다. 핀 위치는 고정하고 라벨만 겹침을 피하도록 배치합니다.

연결점의 `segmentIndex`는 **해당 방향의 `paths` 배열에 대한 0 기반 인덱스**입니다.

```ts
const a = paths[stop.direction].points[connection.segmentIndex];
const b = paths[stop.direction].points[connection.segmentIndex + 1];
// connection.point = a + (b - a) * connection.segmentFraction
```

`segmentFraction`은 0~1입니다. 연결선은 정류장과 대표 운행선의 대응 관계이며, 차량이나 보행자의 이동 경로로 그리지 않습니다. 차량 위치는 기존 watch의 방향·정류장 순번을 이 경로에 대응시킨 추정 위치입니다.

## 조회와 캐시

- 기존 `referenceVersionId` 요청·409 재조회와 304 캐시 처리를 유지합니다. routeId와 referenceVersionId는 문자열입니다.
- 같은 referenceVersionId라도 노선 생성 결과가 바뀌면 geometryRevision/ETag가 바뀝니다. 경로와 정류장 연결점을 함께 교체합니다.
- `UNAVAILABLE`은 `no-store`이며, 그 외 캐시 가능한 응답은 기존 5분 재검증 정책을 따릅니다.
- geometry 준비 상태와 실시간 watch 준비 상태는 별개입니다.

## 개발용 진단

`?includeDiagnostics=true`를 붙이면 원본 `paths`와 별도 `displayPaths`, 출처·품질·근거를 함께 받습니다. 진단 응답의 연결점 인덱스는 `displayPaths` 기준이며 기본 응답과 ETag가 다릅니다. 일반 지도 화면에서는 기본 응답만 사용하면 됩니다.

## 확인할 화면

- 판교역: 상·하행 X자 꼬임과 방향별 선 연결.
- 서현교: 곡선의 뾰족한 꺾임.
- 도촌동9단지앞: 출발/도착 구분과 원본 정류장 위치의 연결선.
- 잠실광역환승센터: 원본 위치와 노선의 간격이 큰 사례. 현재 제공 자료로 실제 지하 동선까지 표현하기는 어렵습니다.

[동일 위치의 실제 앱 전후 화면](screenshots/SAL-338/capture-manifest.json), [서버 생성 구조](route-shape-failover.md), [정류장 위치 조사](route-stop-location-analysis.md)를 참고합니다. iOS 실기기·시뮬레이터 검증은 별도 진행이 필요합니다.
