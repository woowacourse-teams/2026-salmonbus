# 로그인 우선 연동 (SAL-237)

시안은 `salmonbus-design-261022.pen`의 `Y3UzG`다. 홈, 로그인, 마이페이지, 닉네임 수정, 로그아웃과 탈퇴를 포함한다. 그룹과 즐겨찾기는 후속 작업이다. 비회원 마이페이지에는 홈과 같은 로그인/회원가입 버튼을 표시한다. 약관과 개인정보 처리방침 링크는 사용자 요청으로 연결하지 않았다.

API 기준은 dev에 머지된 [백엔드 PR #145](https://github.com/woowacourse-teams/2026-salmonbus/pull/145)의 [공유 명세](../../contract/auth-v1.openapi.json)와 [브라우저 이동·쿠키 계약](../../contract/auth-v1.md)이다. 단위 테스트와 E2E의 회원 응답은 공유 명세의 예시를 직접 읽는다. **실제 API 연결 확인 전에는 PR을 작성하거나 생성하지 않는다.**

작업 중 추가된 전체 기기 로그아웃(`DELETE /api/me/sessions`)은 현재 시안의 범위 밖이다. 최대 180일의 갱신 제한은 기존 401 처리로 대응한다. Apple 토큰 취소에 실패하면 회원을 유지하는 최신 계약도 같은 실패 복귀 흐름으로 처리한다.

## 웹 동작

- `GET /api/auth/session`으로 상태를 조회한다. 액세스 토큰이 만료되고 `refreshable=true`이면 갱신을 한 번만 시도한다. 이 값은 리프레시 토큰의 유효성을 보장하지 않는다.
- 쿠키 인증을 사용한다. 토큰을 JavaScript 저장소에 보관하지 않는다. 상태를 바꾸는 요청에는 `__Host-XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 보낸다.
- 동시에 401을 받은 회원 요청은 진행 중인 갱신 하나를 공유한다. 성공한 경우 원래 요청을 한 번 재시도한다. Apple 탈퇴 시작(`/api/me/withdrawal/apple`)도 같은 규칙을 따른다. 갱신도 401이면 비회원으로 돌아간다.
- 로그아웃은 진행 중인 갱신이 끝난 뒤 최신 쿠키를 폐기한다. 갱신보다 먼저 시작한 요청이 로그아웃 뒤 다시 인증을 갱신하지 않도록 한다.
- 카카오 탈퇴는 `DELETE /api/me`로 끝난다. `REAUTH_REQUIRED`이면 `POST /api/me/withdrawal/apple`이 발급한 같은 origin의 시작 주소로 이동한다. Apple 취소·실패 시 회원 상태를 유지한다.
- API가 404이면 로그인 준비 안내를 표시하고 공개 검색과 판정보드는 유지한다. 네트워크 실패를 비회원 상태로 오인하지 않는다.

## 복귀

서버가 허용하는 복귀 주소는 `/`와 `/routes/{9자리 노선 ID}`다. 노선 주소에는 `direction`과 `stopId`만 전달한다. 마이페이지와 검색어는 상대 경로만 `sessionStorage`에 10분간 보관해 로그인 후 복원한다. 이 저장소에는 인증 정보를 넣지 않는다.

취소·실패 결과 쿼리는 안내를 표시하면서 주소에서 지운다. 회원탈퇴 결과도 쿼리를 지운 뒤 안내한다. 현재 노선에서 같은 방면의 같은 정류장이 여러 번 나타나면 방문을 임의로 선택하지 않는다.

## 범위

이번 작업은 브라우저의 웹 인증만 다룬다. `app/`의 네이티브 SDK·인증 메시지 연결·키 설정은 포함하지 않는다. 앱 인증은 서버의 인증 결과 교환과 WebView 쿠키 발급 계약을 확정한 뒤 별도 작업으로 진행한다.

## 검증 경계

Jest는 입력, 복귀 경로, API 응답 검사, CSRF, 갱신과 로그아웃의 경합을 확인한다. Playwright는 기존 공개 기능과 회원 화면 전환을 모의 응답으로 확인한다. HTTP 테스트 서버에서는 CSRF 쿠키 읽기를 모의하므로 Secure·HttpOnly·SameSite 속성이 검증되는 것은 아니다. WebKit은 모의 302를 지원하지 않아 테스트에서만 같은 목적지의 페이지 이동으로 대체한다.

다음은 실제 환경에서 확인해야 한다.

- 인증 기능과 카카오·Apple 제공자 설정이 켜진 서버, 등록된 콜백 URL과 CloudFront 요청 전달 설정
- 실제 소셜 인증의 성공, 취소, 만료와 쿠키 발급·갱신·삭제

모의 응답 통과를 실제 로그인 완료로 기록하지 않는다. Jira는 실연동 확인 전까지 진행 상태로 둔다.

## 2026-10-22 dev 실 API 점검

배포 직후에는 CSRF·임시 세션 쿠키가 없고 POST 로그아웃이 HTML 200으로 반환됐다. 사용자가 CloudFront Behavior 우선순위를 수정한 뒤 같은 요청을 다시 보내 정상 응답을 확인했다.

`https://dev.salmonbus.com`에서 로컬 프로덕션 빌드를 실행해 Chromium·Firefox·WebKit의 새 브라우저 세션으로 검증했다. 아래 API 응답은 실제 dev 서버에서 받았다.

| 요청                                                                      | 확인한 응답                                                                                          |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `GET /api/v1/routes`                                                      | 200 JSON                                                                                             |
| `GET /api/auth/session`                                                   | 200, `authenticated=false`, `refreshable=false`, `Cache-Control: no-store`. `__Host-XSRF-TOKEN` 발급 |
| `GET /api/me`                                                             | 401 `UNAUTHENTICATED` JSON                                                                           |
| `GET /api/auth/kakao/start`                                               | 302, 카카오 인가 주소, dev 콜백, PKCE S256. `__Host-sb_session` 발급                                 |
| `GET /api/auth/apple/start`                                               | 302, Apple 인가 주소, dev 콜백, `response_mode=query`. `__Host-sb_session` 발급                      |
| 제공자별 합성 취소 콜백                                                   | 302, `login=cancelled`. 임시 세션 쿠키 삭제                                                          |
| 비회원 `POST /api/auth/logout`                                            | 204. 액세스·리프레시 쿠키를 `Max-Age=0`으로 삭제                                                     |
| 무효 액세스 쿠키를 보내고 CSRF 헤더를 생략한 로그아웃                     | 400 `CSRF_REJECTED` JSON                                                                             |
| 같은 무효 액세스 쿠키와 일치하는 CSRF 쿠키·헤더를 보낸 로그아웃           | 204                                                                                                  |
| 리프레시 토큰 없는 `POST /api/auth/refresh`                               | 401 `UNAUTHENTICATED` JSON                                                                           |
| 비회원 `PATCH /api/me`, `DELETE /api/me`, `POST /api/me/withdrawal/apple` | 각각 401. 회원 데이터 변경 없음                                                                      |

세 브라우저 모두 CSRF 쿠키의 `Secure`, `SameSite=Lax`, `Path=/`와 스크립트 접근을 확인했다. 임시 세션 쿠키는 `Secure`, `HttpOnly`, `SameSite=Lax`이며 취소 콜백 뒤 삭제됐다.

로그인 시작 요청은 실제 302 응답과 쿠키를 확인한 뒤 제공자 화면으로의 이동을 멈췄다. 발급된 state와 같은 브라우저 세션의 쿠키로 카카오 `access_denied`, Apple `user_cancelled_authorize` 오류를 서버 콜백에 보냈다. 서버가 반환한 복귀 주소를 로컬 화면으로 열어, 로그인 취소 안내와 `/mypage` 복귀 경로 보존, 결과 쿼리 삭제를 확인했다. 제공자의 실제 취소 조작을 검증한 것은 아니다.

POST·PATCH·DELETE 점검은 실제 회원 토큰과 리프레시 토큰 없이 독립된 비회원 세션에서 실행했다. 실제 소셜 계정 로그인과 로그인된 회원의 토큰 갱신·닉네임 수정·회원탈퇴는 아직 검증하지 않았다. 이 점검을 로그인 성공 검증으로 기록하지 않는다.

[팀의 CloudFront 설정 기록](https://higgs95.atlassian.net/wiki/spaces/HEM52VQZUW/pages/58130445)의 쿠키 전달과 메서드 설정이 실제 응답에 반영된 것을 확인했다. AWS 콘솔의 개별 설정값을 직접 읽은 것은 아니다. Dia MCP는 디버깅 포트 `127.0.0.1:9222` 연결이 거부돼 사용하지 못했고, 위 브라우저 검증은 독립된 Playwright 세션으로 실행했다.
