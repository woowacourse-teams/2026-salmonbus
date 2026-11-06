# 정류장 지도 API v2

기계 판독 계약은 [`station-map-v2.openapi.json`](station-map-v2.openapi.json)이다. 생성 OpenAPI와 공유 파일, 실제 HTTP 응답을 테스트로 대조한다.

- 칸 목록: `GET /api/v2/stations/tiles/14/{x}/{y}`. `x`·`y`는 0~16383이다. 등록 자료는 지역과 관계없이 반환하며 화면 노출 정책은 프론트가 정한다.
- `READY`: 기초 명부 또는 저장된 성공 목록을 읽을 수 있음. 최신 전수 검증 보장은 아니다. `baselineDate`는 공식 자료 기준일, `verifiedAt`은 해당 칸 전체 실조회 성공 시각이며 독립적으로 null일 수 있다.
- `PENDING`: 기초 명부·성공 기록 없음. 갱신 작업 접수 성공을 뜻하지 않는다.
- `READY`는 `public, max-age=300, must-revalidate`와 약한 ETag를 보낸다. `If-None-Match`가 같으면 304이고 본문이 없다. `PENDING`과 오류는 `no-store`다.
- 칸 목록에는 ETA·좌석 예보가 없다. 정류장 선택 시 `GET /api/v2/stations/{stationId}`로 상세를 조회한다. 등록되지 않은 ID는 404이며 임의의 외부 ID를 그대로 원천에 중계하지 않는다.
- 상세에는 GBIS 수신 상태와 우리 노선의 판정보드 기반 예보를 함께 반환한다. 지역명만으로 서울 ETA 부재를 단정하지 않는다. 유효한 도착정보는 최대 5초 HTTP 캐시, `UNAVAILABLE`은 `no-store`다.

목록 응답은 기초 DB를 먼저 읽고 필요한 칸의 갱신 접수는 별도 비동기 실행기로 보낸다. API 실행 앱은 정류장 업무 테이블을 쓰거나 GBIS를 직접 호출하지 않는다. 내부 수집 경로는 인증된 worker 전용이며 이 공개 계약에 포함하지 않는다.

정류소 원천 호출 예산은 기존 `api-call-quota` 모듈의 `DailyCallQuota`·`JdbcCallQuotaStore`를 사용한다. 기존 장부의 서비스 값 `BUS_STATION`으로 분리하고 한국 날짜·키 기준으로 예약한다. 위치·노선·도착 정보 예산을 차감하지 않는다.
