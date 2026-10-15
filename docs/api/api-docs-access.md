# API 문서 제공 범위와 검토 방법

API 문서는 `api-app`이 현재 Controller/DTO에서 생성한다. 같은 빌드의 Swagger UI와 OpenAPI JSON/YAML을 제공하므로, 배포 버전이 바뀌면 명세도 함께 갱신된다. UI에 보이는 version은 API 빌드 버전과 빌드 시각이다. 예시의 노선·차량·오류 식별자는 합성 자료이며 실제 dev 응답을 뜻하지 않는다.

## 접근 범위

기본 설정에서는 문서가 꺼져 있다. 로컬 검토에는 `api-docs-local`, dev 문서 제공에는 `api-docs-dev` 프로필을 명시적으로 포함한다. `prod` 또는 `production` 프로필이 함께 활성화되면 문서는 꺼진다. 운영 배포에는 문서 프로필을 넣지 않는다.

문서가 켜지면 **로그인 없이 누구나** UI와 원문 명세를 조회할 수 있다. 주소를 아는 사람만 볼 수 있다는 접근 제한은 없다. 문서 공개 전에 필드 설명·합성 예시·서버 URL에 내부 주소, 자격증명, 운영 정보가 없는지 검토한다. 공개된 명세는 외부에 복사될 수 있다.

| 경로 | 내용 |
| --- | --- |
| `/api/docs` | UI 링크로 이동 |
| `/api/docs/ui` | Swagger UI |
| `/api/docs/openapi` | OpenAPI JSON |
| `/api/docs/openapi.yaml` | OpenAPI YAML |
| `/api/docs/chat` | WebSocket 메시지·연결·재접속 계약 |
| `/api/docs/chat/examples` | WebSocket 프레임 합성 예시 |

문서에는 구현된 HTTP 조회만 표시한다. v1의 노선 목록·보드·차량 관측과 v2의 정류장 도착정보·노선 geometry를 포함한다. 채팅이 꺼져 있으면 채팅방 가용성 GET도 명세에서 빠진다. WebSocket `/stream`은 HTTP operation으로 표시하지 않고 별도 계약에서 설명한다. 차량의 구간 진행률은 제공하지 않으며 미구현 설계를 명세에 포함하지 않는다.

## 정류장 도착정보와 지도 geometry

| 경로 | 주요 계약 |
| --- | --- |
| `GET /api/v2/stations/{stationId}/arrivals` | 선택 정류장의 ETA. `routeId`로 노선을 선택하고 `referenceVersionId`·`stopSequence`는 함께 전달해 방문을 구분한다. |
| `GET /api/v2/routes/{routeId}/geometry` | 원본 운행 순서의 전체 도로 형상 `points`와 정류장 마커 `busStops[].point`. 분리 근거가 확인되면 `paths.UP.points`·`paths.DOWN.points`도 함께 제공한다. READY여도 `paths=null`일 수 있으며 전체 `points`는 유지한다. |

도착정보의 데이터 상태와 갱신 상태는 별개다. 최초 수집·만료·호출 제한도 HTTP 200 본문으로 구분하므로 `status`, `refresh`, `eta.status`를 확인한다. 재조회는 `refresh.suggestedRefreshAt`, 카운트다운은 서버 시계와 `expectedArrivalAt`을 사용한다. HTTP 캐시 최대 5초를 폴링 주기로 사용하지 않으며 예상 시각이 지났다고 실제 도착으로 확정하지 않는다.

geometry는 `/api/v1/routes`에서 선택한 `routeId`로 한 번 받아 재사용한다. `points`로 도로 형상을, `busStops[].point`로 정류장 마커를 그린다. 정류장 좌표를 직선으로 이어 도로 형상을 대체하지 않는다. 같은 `stationId`가 여러 번 나와도 방문별로 유지하고 `stopSequence`로 구분한다. `turningPoint.stopSequence`는 정류장 방문 순번이며 `points` 배열 인덱스가 아니다.

`paths`가 있으면 두 방향을 한 응답에서 사용한다. UP과 DOWN은 회차 경계점을 공유하며 DOWN을 UP의 역순으로 만들지 않는다. 회차·방문 순서를 형상에 대응할 근거가 부족하거나 모호하면 `paths=null`이므로 전체 `points`를 표시한다. 두 방향의 색상·선택·겹침 순서는 프론트에서 처리하며 방향 전환이나 지도 확대·이동마다 재조회하지 않는다.

| HTTP / 본문 상태 | 화면 처리 |
| --- | --- |
| 200 / `READY` | 전체 도로 형상과 모든 정류장 좌표를 표시한다. `paths`의 존재 여부는 별도로 확인한다. |
| 200 / `PARTIAL`, `MISSING_STOP_COORDINATES` | 전체 도로 형상은 표시하고 `point=null`인 정류장의 마커만 생략한다. |
| 200 / `UNAVAILABLE`, `NOT_COLLECTED` | 현재 판본의 형상이 아직 없어 `points=[]`, `paths=null`이다. 정류장 좌표가 있으면 마커만 표시한다. |
| 304 | 이전과 같은 표시 자료이므로 기존 본문을 재사용한다. 응답 본문은 없다. |
| 409 / `REFERENCE_VERSION_CONFLICT` | `referenceVersionId` 없이 geometry를 다시 받고 새 판본·방문 순번으로 선택을 갱신한다. 과거 판본 조회는 제공하지 않는다. |
| 503 / `SERVICE_UNAVAILABLE` | 저장소 또는 저장된 형상을 읽지 못한 오류다. 200의 미수집 상태와 구분한다. |

정류장 ETA를 선택할 때 geometry의 `referenceVersionId`와 해당 방문의 `stopSequence`를 함께 전달한다. 새 카탈로그 판본에는 이전 판본의 정류장 좌표·형상을 붙이지 않는다. `referenceVersionId`가 같아도 정류장 좌표나 형상이 바뀌면 `geometryRevision`과 ETag가 바뀐다. READY/PARTIAL은 최대 5분의 HTTP 캐시와 `If-None-Match` 재검증을 지원하며, UNAVAILABLE과 오류는 `no-store`다. GET은 외부 형상 수집을 유발하지 않는다.

현재 dev 공급 자료는 **41노선, 정류장 방문 3,215건, 형상점 35,542개**의 GBIS 정적 재생 자료다. 기존 8노선의 정적 API 저장본과 추가 33노선의 공식 공개 지도 형상을 사용한다. 방문 건수는 고유 물리 정류장 수가 아니며, 형상점은 정류장 좌표와 별도 자료다. 좌표·출처·공식 지도 자료의 좌표계 변환과 검증 범위는 [형상 자료 안내](../../dev/data/route-shapes.md)에 있다. 이 수치는 저장소 공급 자료 기준이며 공개 dev 배포 완료나 실시간 원천 갱신을 뜻하지 않는다.

dev Worker는 형상 수집과 카탈로그 전체 포함을 켜서 현재 GBIS 카탈로그 노선을 순차 갱신한다. 초기 수집 완료 전이나 노선 판본이 바뀐 직후에는 일시적으로 NOT_COLLECTED일 수 있다. 같은 판본에서 수집이 실패하면 이미 저장한 정상 형상은 유지한다. 새로운 카탈로그 노선도 수집 대상에 포함하지만, dev 재생을 위해서는 그 판본에 맞는 정류장·원본 형상 공급 자료가 필요하다. 운영 기본 설정의 형상 수집은 계속 꺼져 있으며 dev 재생 자료가 운영의 인증 API 수집을 대신하지 않는다.

별도 공유 명세는 `contract/station-arrivals-v2.openapi.json`, `contract/route-geometry-v2.openapi.json`에 있다. 문서 테스트는 이 파일의 200 응답 예시와 Controller에서 생성한 예시를 대조한다. 두 파일은 검증 입력이며 앱의 Swagger는 Controller/DTO에서 생성한다.

## 새 API가 생길 때

springdoc은 앱이 뜰 때 Spring Controller와 DTO에서 명세를 만든다. 현재 `springdoc.paths-to-match`는 `/api/v1/**`, 위의 v2 두 경로, 채팅방 가용성 경로를 포함한다. 이 범위의 `@RestController` 경로와 DTO 기본 구조는 다음 빌드·배포 때 자동 반영된다. 다른 경로는 포함 범위를 먼저 검토해야 한다. 채팅처럼 `RouterFunction`으로 등록한 HTTP 경로에는 명시적인 `@RouterOperation`이 필요하다. WebSocket 프레임은 OpenAPI HTTP 경로로 자동 생성되지 않는다.

자동 생성만으로 필드 의미·단위, null과 생략의 차이, 상태별 정상/오류 예시가 채워지지는 않는다. 새 API를 추가할 때 Controller의 응답 설명과 합성 예시, DTO의 `@Schema`, WebSocket 공개 안내를 실제 계약에 맞게 갱신한다. 현재 문서 테스트는 경로 5개(채팅 on이면 6개)를 확인하므로 새 경로가 생기면 테스트가 실패한다. 이는 공개 문서에 새 API가 실리기 전에 범위와 예시를 검토하도록 하는 신호다. 문서 파일을 별도로 업로드할 필요는 없으며 검토를 마친 코드를 배포하면 같은 앱의 Swagger 화면이 새 명세를 보여준다.

## Try it out과 dev 연결

Swagger의 Try it out은 현재 모든 HTTP 메서드에서 꺼져 있다. 이 UI 설정은 **실제 API 호출 권한을 막는 장치가 아니다.** 기존 서비스 API와 WebSocket의 공개 정책은 그대로다.

dev 주소에서 실제로 dev 백엔드를 바라보는지, `/api/docs/*`가 같은 원본으로 전달되는지, UI와 명세가 브라우저에서 정상 로드되는지 확인한 뒤 GET 시험 기능을 별도로 검토한다. SAL-179 기록에는 dev 프론트의 `/api/*` 원본을 운영 백엔드에서 dev 백엔드로 바꾸는 단계가 따로 있다. 이 저장소 변경만으로 현재 원본을 확인하거나 전환하지 않는다.

문서 배포·프록시 변경은 별도의 배포 절차다. 이 단계의 구현은 GitHub 로그인과 팀원 허용 목록을 제공하지 않으므로 SAL-193의 기존 인증 완료 조건을 충족하지 않는다. 티켓 범위를 조정하거나 추후 팀원 인증 작업을 분리해야 한다.
