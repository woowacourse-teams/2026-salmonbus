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

문서에는 구현된 HTTP 조회만 표시한다. 채팅이 꺼져 있으면 채팅방 가용성 GET도 명세에서 빠진다. WebSocket `/stream`은 HTTP operation으로 표시하지 않고 별도 계약에서 설명한다. 미구현 ETA·진행률 설계는 명세에 포함하지 않는다.

## 새 API가 생길 때

springdoc은 앱이 뜰 때 Spring Controller와 DTO에서 명세를 만든다. 현재 `springdoc.paths-to-match`가 `/api/v1/**`와 채팅방 가용성 경로만 포함하므로, **새 `@RestController` 경로가 `/api/v1/**` 아래라면 경로와 DTO의 기본 구조는 다음 빌드·배포 때 자동 반영**된다. 다른 경로는 포함 범위를 먼저 검토해야 한다. 채팅처럼 `RouterFunction`으로 등록한 HTTP 경로에는 명시적인 `@RouterOperation`이 필요하다. WebSocket 프레임은 OpenAPI HTTP 경로로 자동 생성되지 않는다.

자동 생성만으로 필드 의미·단위, null과 생략의 차이, 상태별 정상/오류 예시가 채워지지는 않는다. 새 API를 추가할 때 Controller의 응답 설명과 합성 예시, DTO의 `@Schema`, WebSocket 공개 안내를 실제 계약에 맞게 갱신한다. 현재 문서 테스트는 경로 3개(채팅 on이면 4개)를 확인하므로 새 경로가 생기면 테스트가 실패한다. 이는 공개 문서에 새 API가 실리기 전에 범위와 예시를 검토하도록 하는 신호다. 문서 파일을 별도로 업로드할 필요는 없으며 검토를 마친 코드를 배포하면 같은 앱의 Swagger 화면이 새 명세를 보여준다.

## Try it out과 dev 연결

Swagger의 Try it out은 현재 모든 HTTP 메서드에서 꺼져 있다. 이 UI 설정은 **실제 API 호출 권한을 막는 장치가 아니다.** 기존 서비스 API와 WebSocket의 공개 정책은 그대로다.

dev 주소에서 실제로 dev 백엔드를 바라보는지, `/api/docs/*`가 같은 원본으로 전달되는지, UI와 명세가 브라우저에서 정상 로드되는지 확인한 뒤 GET 시험 기능을 별도로 검토한다. SAL-179 기록에는 dev 프론트의 `/api/*` 원본을 운영 백엔드에서 dev 백엔드로 바꾸는 단계가 따로 있다. 이 저장소 변경만으로 현재 원본을 확인하거나 전환하지 않는다.

문서 배포·프록시 변경은 별도의 배포 절차다. 이 단계의 구현은 GitHub 로그인과 팀원 허용 목록을 제공하지 않으므로 SAL-193의 기존 인증 완료 조건을 충족하지 않는다. 티켓 범위를 조정하거나 추후 팀원 인증 작업을 분리해야 한다.
