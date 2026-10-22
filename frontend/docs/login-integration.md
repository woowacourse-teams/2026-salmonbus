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

## iOS 연결 상태

`app/`에 Kakao SDK 설정과 신뢰하는 `/login` 페이지의 요청만 받는 WebView 연결이 있다. 카카오톡 사용이 가능하면 앱 인증을 먼저 시도하고 미설치 시 카카오계정 인증을 사용한다. 상세 설정은 `app/README.md`를 따른다.

**현재 서버에는 네이티브 SDK 인증 결과를 연어버스 회원 인증으로 바꾸는 계약이 없다.** 따라서 앱에서 카카오 인증을 완료해도 웹에 회원 상태를 만들지 않는다. 제공자 토큰은 WebView나 로그에 전달하지 않고 SDK에서 지운다. iOS의 Apple 로그인도 연결 준비 안내를 표시한다.

백엔드와 다음을 확정한 뒤 연결한다.

1. 앱이 전달할 인증 증명과 서버의 검증 기준. ID 토큰이면 서명, 발급자, 네이티브 앱 키에 맞는 audience, 만료와 서버가 발급한 nonce를 검증해야 한다.
2. 검증된 회원을 WebView의 HttpOnly 쿠키 인증으로 연결하는 절차. 앱과 웹이 같은 회원을 가리켜야 한다.
3. 로그인 취소, 앱 종료, 중복 요청, Apple 재인증 탈퇴의 복귀 방법. 토큰을 URL에 넣지 않는다.

## 검증 경계

Jest는 입력, 복귀 경로, API 응답 검사, CSRF, 갱신과 로그아웃의 경합을 확인한다. Playwright는 기존 공개 기능과 회원 화면 전환을 모의 응답으로 확인한다. HTTP 테스트 서버에서는 CSRF 쿠키 읽기를 모의하므로 Secure·HttpOnly·SameSite 속성이 검증되는 것은 아니다. WebKit은 모의 302를 지원하지 않아 테스트에서만 같은 목적지의 페이지 이동으로 대체한다.

다음은 실제 환경에서 확인해야 한다.

- 인증 기능과 카카오·Apple 제공자 설정이 켜진 서버, 등록된 콜백 URL과 CloudFront 요청 전달 설정
- 실제 소셜 인증의 성공, 취소, 만료와 쿠키 발급·갱신·삭제
- iPhone의 카카오톡 설치/미설치, 앱 왕복, 재실행 후 로그인 유지
- 확정한 네이티브 인증 교환과 Apple 앱 로그인·탈퇴 연결

모의 응답 통과를 실제 로그인 완료로 기록하지 않는다. Jira는 실연동 확인 전까지 진행 상태로 둔다.

## 2026-10-22 dev 실 API 점검

백엔드 배포 후 `https://dev.salmonbus.com`을 조회하고, 로컬 프로덕션 빌드의 로그인 화면을 같은 origin에서 실행했다. API 응답은 모의하지 않았다. 로그인 버튼은 활성화됐지만, 브라우저에 저장된 쿠키가 없어 CSRF 쿠키 검증은 실패했다.

| 요청                        | 확인한 응답                                                                | 남은 문제                                      |
| --------------------------- | -------------------------------------------------------------------------- | ---------------------------------------------- |
| `GET /api/v1/routes`        | 200 JSON                                                                   | 공개 조회 응답 정상                            |
| `GET /api/auth/session`     | 200, `authenticated=false`, `refreshable=false`, `Cache-Control: no-store` | `Set-Cookie`와 `__Host-XSRF-TOKEN` 쿠키가 없음 |
| `GET /api/me`               | 401 `UNAUTHENTICATED` JSON                                                 | 비회원 거절 응답 정상                          |
| `GET /api/auth/kakao/start` | 302, 카카오 인가 주소, dev 콜백, PKCE S256                                 | 임시 세션 쿠키 `__Host-sb_session`이 없음      |
| `GET /api/auth/apple/start` | 302, Apple 인가 주소, dev 콜백, `response_mode=query`                      | 임시 세션 쿠키 `__Host-sb_session`이 없음      |
| `POST /api/auth/logout`     | 200, 프론트엔드 `index.html`                                               | 계약의 204 응답이 아님                         |

POST 점검은 실제 회원 토큰과 리프레시 토큰 없이 독립된 요청으로 실행했다. 무효 액세스 쿠키와 합성 CSRF 값을 보낸 경우에도 같은 HTML 200이 반환됐다. 실제 계정 로그인, 닉네임 수정, 회원탈퇴는 실행하지 않았다.

인증 경로에 필요한 CloudFront Behavior가 dev 배포에 적용됐는지 확인해야 한다. `/api/auth/*`와 `/api/me*`가 기존 `/api/*`보다 앞에서 일치하고, HTTP 방법 전부와 쿠키 All, 쿼리스트링 All, `X-XSRF-TOKEN` 전달, TTL 0 설정을 사용해야 한다. 설정이 확인된 뒤 쿠키 발급과 POST 응답부터 다시 점검한다.

원본 서버의 직접 응답과 AWS 콘솔의 실제 설정은 아직 확인하지 못했다. 따라서 쿠키 누락의 원인은 확정하지 않았다. 다만 [AWS 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/Cookies.html)에 따르면 CloudFront가 쿠키를 전달하지 않는 Behavior에서는 응답의 `Set-Cookie`도 제거한다. [팀의 CloudFront 설정 기록](https://higgs95.atlassian.net/wiki/spaces/HEM52VQZUW/pages/58130445)에도 dev와 운영의 적용 여부는 별도 확인 대상으로 남아 있다.
