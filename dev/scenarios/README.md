# GBIS 개발 시나리오

WireMock은 worker가 호출하는 GBIS만 대신합니다. 브라우저는 실제 로컬 API를 사용합니다. 노선 기본 정보·정류장 목록·차량 위치 세 경로만 제공하고, 개발용 키와 지원 노선·JSON 형식이 맞지 않는 요청에는 404를 반환합니다. 실제 GBIS로 전달하거나 응답을 녹화하지 않습니다.

기본 `normal`은 전체 8개 노선에서 상행·하행 개발 차량을 한 대씩 재생합니다. 최초 유효 위치 요청부터 실제 경과 시간으로 정류장을 30초마다 이동합니다. 중복·동시 요청이 이동을 앞당기지 않습니다. 양방향 종점에 도달한 차량은 목록에서 빠지고, 노선의 두 운행이 끝난 뒤 60초를 쉬고 새 차량 ID로 반복합니다. 노선 구조는 실제 자료이며 차량·좌석·이동 시간은 가상 값입니다. 실제 운행 시간이나 배차를 재현한 자료가 아닙니다.

| 모드 | GBIS 위치 응답 |
|---|---|
| `normal` | 개발 차량 위치와 0~40석의 잔여석 |
| `empty` | 결과 코드 4, 차량 목록 없음 |
| `unknown-seat` | 위치는 정상, 잔여석 -1 |
| `upstream-error` | HTTP 503과 결과 코드 1 |

```sh
./dev/local.sh scenario empty 1650
./dev/local.sh scenario unknown-seat 3330
./dev/local.sh scenario upstream-error 9007
./dev/local.sh scenario normal all
```

노선을 생략하면 전체에 적용합니다. 한 노선에 적용해도 다른 노선과 메타데이터 응답은 유지합니다. 변경은 WireMock의 기존 관리 API에 규칙을 등록하는 방식이며 다음 수집부터 반영됩니다. 재생 시간과 DB 내용은 초기화하지 않습니다. WireMock을 재시작하면 선택 규칙과 재생 시점이 초기화되고 기본 normal로 시작합니다. 새 실행과 새 운행에는 다른 개발 차량 ID를 사용합니다.

설정은 `replay.json`, 요청 매칭은 `dev/wiremock/mappings`, 시간 재생은 독립 `dev/wiremock-extension` JAR가 담당합니다. 이 프로젝트는 backend Gradle 모듈이나 운영 JAR에 등록하지 않습니다. 확장의 구현 방식은 [WireMock 응답 변환](https://wiremock.org/docs/extensibility/transforming-responses/)과 [공식 Docker 실행](https://wiremock.org/docs/standalone/docker/) 문서를 따릅니다.
