# 로그인 계약 v1

프론트와 서버가 함께 지키는 소셜 로그인(카카오·애플) 계약이다.

fetch로 부르는 API 11개의 요청·응답·오류 코드는 [`auth-v1.openapi.json`](auth-v1.openapi.json)이 원본이다. 서버 시험(`AuthApiDocsContractTest`)이 앱이 만든 명세와 이 파일을 대조한다. 이 문서는 명세에 담기지 않는 브라우저 이동 흐름과 쿠키 규칙을 적는다. 모양을 바꾸면 두 파일을 같은 PR에서 고친다.

## 켜짐

- 로그인은 `AUTH_ENABLED=true`일 때만 켜진다. 꺼져 있으면 `/api/auth/*`와 `/api/me` 아래 경로가 모두 404이고, Swagger 명세에도 나오지 않는다.
- 제공자는 `AUTH_KAKAO_ENABLED`·`AUTH_APPLE_ENABLED`로 따로 켠다. 켜지 않은 제공자의 `start`는 404다.
- 앱 네이티브 로그인은 그 제공자가 켜져 있고 앱용 값(`AUTH_APPLE_APP_ID`·`AUTH_KAKAO_NATIVE_APP_KEY`)이 있을 때만 켜진다. 아니면 그 제공자의 `/native` 경로와 앱 애플 탈퇴가 404다. 웹은 404를 받으면 브라우저 로그인 흐름으로 돌아간다.
- 공개 조회 API는 로그인과 상관없이 그대로다. 만료되거나 깨진 액세스 토큰이 실려 와도 공개 조회와 `/api/auth/*`는 그 이유로 거절하지 않는다.

## API

| 요청 | 성공 | 거절 |
| --- | --- | --- |
| `GET /api/auth/session` | 200. 로그인 상태, CSRF 쿠키가 없으면 발급 | 없음 |
| `POST /api/auth/refresh` | 쿠키 요청은 204와 새 인증 쿠키, 본문 요청은 200과 새 토큰 | 400 `CSRF_REJECTED`·`INVALID_REQUEST`, 401 `UNAUTHENTICATED` |
| `POST /api/auth/logout` | 204. 인증 쿠키 삭제. 토큰이 없어도 204 | 400 `CSRF_REJECTED`·`INVALID_REQUEST` |
| `GET /api/me` | 200. 내 정보 | 401 `UNAUTHENTICATED` |
| `PATCH /api/me` | 200. 바뀐 내 정보 | 400 `NICKNAME_LENGTH`·`NICKNAME_CHARACTERS`·`NICKNAME_RESERVED`·`CSRF_REJECTED`·`INVALID_REQUEST`, 401 `UNAUTHENTICATED`, 409 `NICKNAME_TAKEN` |
| `DELETE /api/me` | 204. 카카오 회원 탈퇴, 인증 쿠키 삭제 | 400 `CSRF_REJECTED`, 401 `UNAUTHENTICATED`, 409 `REAUTH_REQUIRED`(애플 회원) |
| `DELETE /api/me/sessions` | 204. 모든 기기에서 로그아웃, 인증 쿠키 삭제 | 400 `CSRF_REJECTED`, 401 `UNAUTHENTICATED` |
| `POST /api/me/withdrawal/apple` | 200. 애플 재인증 탈퇴 시작 주소 | 400 `INVALID_REQUEST`(카카오 회원)·`CSRF_REJECTED`, 401 `UNAUTHENTICATED` |
| `POST /api/auth/{provider}/native/nonce` | 200. 앱 로그인 nonce | 400 `CSRF_REJECTED`, 404 `ENDPOINT_NOT_FOUND`(앱 로그인 꺼짐) |
| `POST /api/auth/{provider}/native` | 204. 앱 ID 토큰으로 로그인, 인증 쿠키 발급 | 400 `INVALID_REQUEST`·`CSRF_REJECTED`, 401 `UNAUTHENTICATED`, 404 `ENDPOINT_NOT_FOUND`, 503 `SERVICE_UNAVAILABLE` |
| `POST /api/me/withdrawal/apple/native` | 204. 앱 애플 회원 탈퇴, 인증 쿠키 삭제 | 400 `INVALID_REQUEST`(카카오 회원·코드 없음)·`CSRF_REJECTED`, 401 `UNAUTHENTICATED`, 404 `ENDPOINT_NOT_FOUND`, 409 `REAUTH_REQUIRED`, 503 `SERVICE_UNAVAILABLE` |

- 모든 응답은 `Cache-Control: no-store`다.
- 오류 본문은 다른 API와 같은 `{code, message, requestId}`다. 클라이언트는 `code`로 분기한다.
- 표에 없는 405 `METHOD_NOT_ALLOWED`, 500 `INTERNAL_ERROR`, 503 `SERVICE_UNAVAILABLE`도 같은 형식으로 올 수 있다.
- 403은 쓰지 않는다. CloudFront가 403을 `index.html` 200으로 바꾸기 때문이다.

## 인증

- 액세스 토큰은 `Authorization: Bearer` 헤더를 먼저 보고, 헤더가 없거나 토큰 값이 비어 있으면 `__Host-sb_at` 쿠키를 본다. 웹과 웹뷰는 쿠키, 쿠키를 쓰지 않는 클라이언트는 헤더를 쓴다.
- 헤더에 토큰 값이 있으면 그 토큰이 검증에 실패해도 쿠키를 보지 않는다. `/api/me` 아래 요청이면 401 `UNAUTHENTICATED`이고, `GET /api/auth/session`은 `authenticated=false`로 답한다. 그래서 웹뷰 요청에는 헤더를 붙이지 않는다.
- 리프레시 토큰은 `POST /api/auth/refresh`·`logout` 본문의 `refreshToken`을 먼저 보고, 없거나 비어 있으면 `__Host-sb_rt` 쿠키를 본다.
- 갱신에 실패하면 이유를 구분하지 않고 401 `UNAUTHENTICATED`다. 쿠키로 보낸 요청이면 인증 쿠키 두 개를 지운다.
- 리프레시 토큰은 갱신할 때마다 바뀐다. 직전 토큰을 갱신 뒤 10초 안에 다시 보내면 동시 갱신으로 보고 지금 토큰을 다시 준다. 10초가 지났거나 그보다 오래된 토큰이면 그 로그인 계통 전체가 폐기되어 다시 로그인해야 한다.
- 리프레시 토큰은 갱신할 때마다 다시 30일, 처음 로그인한 뒤 최대 180일 유효하다. 180일이 지나면 갱신이 401이고 다시 로그인해야 한다.
- 액세스 토큰은 만료(15분)까지 취소되지 않는다. 그래서 `/api/me` 아래 요청은 회원이 아직 있는지 매번 확인하고, 탈퇴했으면 401이다.
- `DELETE /api/me/sessions`는 회원의 로그인 계통을 모두 지워 다른 기기의 세션까지 끊는다. 이미 발급된 액세스 토큰은 취소되지 않아 최대 15분 남는다.

## CSRF

- 인증 쿠키(`__Host-sb_at`·`__Host-sb_rt`)가 실린 `GET`·`HEAD`·`OPTIONS` 밖의 요청은 `Authorization: Bearer` 헤더가 없으면 `X-XSRF-TOKEN` 헤더가 있어야 한다. 값은 `__Host-XSRF-TOKEN` 쿠키 값 그대로다.
- 헤더가 없거나 쿠키 값과 다르면 400 `CSRF_REJECTED`다. 폼 파라미터(`_csrf`)는 받지 않는다.
- 인증 쿠키가 없거나 `Authorization: Bearer` 헤더로 인증하는 요청은 CSRF 토큰을 요구하지 않는다.
- 예외: 앱 로그인 두 요청(`/api/auth/{provider}/native/nonce`·`/native`)은 인증 쿠키가 없어도 `X-XSRF-TOKEN`이 있어야 한다. 다른 사이트가 공격자 계정의 토큰으로 사용자를 로그인시키지 못하게 하려는 것이다. 앱 로그인 요청과 앱 애플 탈퇴는 `Origin` 헤더가 있으면 사이트 주소와 같아야 한다. 다르면 400 `CSRF_REJECTED`다.
- CSRF 쿠키는 `GET /api/auth/session`이 발급한다. 상태를 바꾸는 요청 전에 한 번 호출해 둔다.

## 쿠키

| 이름 | 내용 | 속성 | 수명 |
| --- | --- | --- | --- |
| `__Host-sb_at` | 액세스 토큰 | `HttpOnly; Secure; SameSite=Lax; Path=/` | `Max-Age=900`(15분) |
| `__Host-sb_rt` | 리프레시 토큰 | `HttpOnly; Secure; SameSite=Lax; Path=/` | 리프레시 토큰 만료까지. 갱신할 때마다 다시 30일, 처음 로그인한 뒤 최대 180일 |
| `__Host-XSRF-TOKEN` | CSRF 토큰 | `Secure; SameSite=Lax; Path=/`. 스크립트가 읽을 수 있다 | 브라우저 세션 |
| `__Host-sb_session` | 로그인 중 임시 세션. 앱 로그인 nonce도 여기 둔다 | `HttpOnly; Secure; SameSite=Lax; Path=/` | 10분. 로그인이 끝나면 지운다 |

- 모두 `__Host-` 접두사라 `Domain` 없이 호스트 전용이다. 형제 도메인(dev 등)이 같은 이름의 쿠키를 심지 못한다.
- 인증 쿠키를 지울 때는 같은 이름에 `Max-Age=0`을 내려 준다.
- `__Host-sb_session`은 서버가 로그인 흐름 안에서만 쓴다. 프론트가 다룰 일은 없다.

## 로그인 흐름(브라우저 이동)

이 경로는 컨트롤러가 아니라 보안 필터가 처리하므로 Swagger에는 없다. fetch가 아니라 브라우저를 이동시킨다(`location.href` 또는 링크).

1. `GET /api/auth/{provider}/start?returnTo=<경로>`. `provider`는 `kakao` 또는 `apple`이다.
   - 서버는 임시 세션을 만들고 `returnTo`를 검증해 둔 뒤 제공자 로그인 화면으로 302 이동시킨다.
   - 카카오는 PKCE(S256)를 쓴다. 애플은 PKCE를 지원하지 않아 쓰지 않고, 콜백이 쿼리로 돌아오게 `response_mode=query`를 보낸다.
2. 제공자가 `GET /api/auth/{provider}/callback?code=...&state=...`로 돌려보낸다. 제공자 콘솔에 등록한 주소와 `AUTH_PUBLIC_BASE_URL` + 이 경로가 같아야 한다.
3. 결과는 항상 302이고 `Cache-Control: no-store`다.

| 결과 | 이동할 곳 | 쿠키 |
| --- | --- | --- |
| 로그인 성공(처음이면 가입) | `returnTo` 그대로 | `__Host-sb_at`·`__Host-sb_rt` 발급 |
| 사용자가 제공자 화면에서 취소 | `returnTo`에 `login=cancelled` | 바꾸지 않음 |
| 그 밖의 실패(제공자 오류, 임시 세션 만료, 회원 처리 실패 등) | `returnTo`에 `login=failed` | 바꾸지 않음 |

- 취소는 카카오의 `access_denied`, 애플의 `user_cancelled_authorize`다.
- `returnTo`에 이미 쿼리가 있으면 결과 쿼리는 뒤에 붙는다. 예: `/routes/123456789?direction=UP&login=failed`
- 실패해도 이미 로그인한 사용자의 쿠키는 그대로라 로그인이 유지된다.
- 임시 세션은 10분이다. 제공자 화면에서 10분 넘게 머물면 `login=failed`로 돌아오고, 이때 `returnTo`는 `/`다.
- 새로 가입한 회원의 닉네임은 서버가 무작위로 정한다. 제공자의 프로필 닉네임은 받지 않는다.

### `returnTo`

- 허용하는 값은 `/`와 `/routes/{9자리 숫자}`다.
- 노선 경로에는 쿼리 `direction`(`UP`|`DOWN`)과 `stopId`(영문·숫자·`_`·`-` 1~40자)만 붙을 수 있고, 각각 한 번만 쓸 수 있다.
- 그 밖의 값(생략, 다른 경로, `//host`, `https://...` 같은 절대 주소, 허용하지 않는 쿼리)은 모두 `/`로 바뀐다.

## 앱 네이티브 로그인 흐름

iOS 앱이 애플·카카오 SDK로 로그인하고, 앱 WebView 안의 웹이 아래 API를 fetch로 부른다. 응답 쿠키가 WebView에 바로 저장되도록 앱 네이티브 코드가 아니라 웹이 부른다.

1. `GET /api/auth/session`으로 CSRF 쿠키를 받는다(앱을 열 때 이미 불렀으면 생략).
2. `POST /api/auth/{provider}/native/nonce`로 `{"nonce": "..."}`를 받는다. `provider`는 `apple` 또는 `kakao`다.
3. 앱이 SDK에 nonce를 넣어 로그인한다.
   - 애플: nonce의 SHA-256(UTF-8, 소문자 16진수)을 Sign in with Apple의 nonce로 넣는다.
   - 카카오: nonce를 그대로 카카오 SDK의 nonce로 넣는다.
4. 받은 ID 토큰으로 `POST /api/auth/{provider}/native`(본문 `{"idToken": "..."}`)를 부른다. 204면 인증 쿠키가 내려와 로그인된 것이다.

| 결과 | 응답 | 다음 할 일 |
| --- | --- | --- |
| 로그인 성공(처음이면 가입) | 204, 인증 쿠키 발급, 임시 세션 삭제 | `GET /api/auth/session`으로 상태를 다시 읽는다 |
| 토큰 검증 실패, nonce 없음·이미 씀 | 401 `UNAUTHENTICATED` | 2번부터 다시 한다 |
| 제공자 공개키를 받지 못함 | 503 `SERVICE_UNAVAILABLE` | 잠시 뒤 2번부터 다시 한다 |
| 앱 로그인이 켜지지 않음 | 404 `ENDPOINT_NOT_FOUND` | 브라우저 로그인 흐름(`start`)을 쓴다 |

- 토큰은 서명(RS256)·발급자·대상·만료·nonce를 확인한다. 대상은 앱용 값(애플 번들 ID, 카카오 네이티브 앱 키)만 받는다.
- 같은 계정이면 웹 로그인과 같은 회원이다. 회원은 제공자와 계정 식별자(`sub`)로 찾는다.
- nonce는 이 브라우저의 임시 세션에 10분 동안 하나만 있다. 맞든 틀리든 한 번 쓰면 다시 쓸 수 없다. 같은 제공자로 다시 받으면 앞의 nonce는 못 쓴다.
- 성공은 204만이다. 다른 상태 코드나 HTML이 오면 실패로 본다.

## 애플 회원 탈퇴 흐름

애플 연결을 끊으려면 애플이 새로 발급한 토큰이 필요하고 서버는 애플 토큰을 저장하지 않는다. 그래서 애플 회원은 애플 계정으로 다시 인증해야 탈퇴할 수 있다.
카카오 회원은 `DELETE /api/me` 한 번으로 탈퇴한다.

1. `DELETE /api/me`가 409 `REAUTH_REQUIRED`면 애플 회원이다.
2. `POST /api/me/withdrawal/apple`(본문 `{"returnTo": "<경로>"}`, 생략 가능)로 `startUrl`을 받는다. 이 요청은 CSRF로 보호된다.
3. 브라우저를 `startUrl`(`/api/auth/apple/start?intent=withdraw&ticket=...&returnTo=...`)로 이동시킨다. 티켓은 5분 동안 유효하다.
4. 애플 인증을 마치면 결과에 따라 이동한다.

| 결과 | 이동할 곳 | 쿠키 |
| --- | --- | --- |
| 탈퇴함 | `/?withdrawn=1`. `returnTo`와 상관없이 첫 화면 | 인증 쿠키 삭제 |
| 사용자가 애플 화면에서 취소 | `returnTo`에 `withdraw=cancelled` | 바꾸지 않음 |
| 티켓이 없거나 만료·위조됨 | `returnTo`에 `withdraw=failed`. 애플 화면으로 가지 않음 | 바꾸지 않음 |
| 애플 토큰 취소에 실패함(애플이 토큰을 주지 않은 경우 포함) | `returnTo`에 `withdraw=failed`. 회원은 지우지 않음 | 바꾸지 않음 |
| 다른 애플 계정으로 인증함, 그 밖의 실패 | `returnTo`에 `withdraw=failed` | 바꾸지 않음 |
| 애플 화면에서 10분 넘게 머물러 임시 세션이 만료됨 | `/?login=failed`. 탈퇴 중이었다는 정보도 함께 사라진다 | 바꾸지 않음 |

- 티켓은 CSRF로 보호되는 2번 요청으로만 받을 수 있다. 다른 사이트가 링크 하나로 탈퇴 흐름을 열지 못하게 하려는 것이다.
- 회원은 애플 토큰을 취소한 뒤에만 지운다. 취소에 실패하면 회원과 로그인이 그대로 남으므로 사용자는 2번부터 다시 시도할 수 있다.

### 앱에서 애플 회원 탈퇴

앱은 브라우저 이동 대신 Sign in with Apple로 다시 인증해 받은 `authorizationCode`로 탈퇴한다. 웹의 2~4번 대신 쓴다.

1. `DELETE /api/me`가 409 `REAUTH_REQUIRED`면 앱에 애플 재인증을 요청한다.
2. 받은 코드로 `POST /api/me/withdrawal/apple/native`(본문 `{"authorizationCode": "..."}`)를 부른다. CSRF로 보호된다.

| 결과 | 응답 | 쿠키 |
| --- | --- | --- |
| 탈퇴함 | 204 | 인증 쿠키 삭제 |
| 코드가 틀렸거나 만료됨(5분, 1회), 다른 애플 계정, 애플 토큰 취소 실패 | 409 `REAUTH_REQUIRED`. 회원은 지우지 않음 | 바꾸지 않음 |
| 애플이 응답하지 않음 | 503 `SERVICE_UNAVAILABLE`. 회원은 지우지 않음 | 바꾸지 않음 |
| 카카오 회원, 코드 없음 | 400 `INVALID_REQUEST` | 바꾸지 않음 |

- 서버는 코드를 앱 `client_id`로 교환하고, 함께 받은 ID 토큰의 계정이 이 회원인지 확인한 뒤 애플 토큰을 취소하고 회원을 지운다.
- 409면 다시 인증해 새 코드로 시도한다. 코드는 한 번만 쓸 수 있다.

## 프론트 권장 순서

- 앱을 열면 `GET /api/auth/session`을 부른다. `authenticated=false`이고 `refreshable=true`면 `POST /api/auth/refresh`를 한 번 부르고, 204면 다시 `session`을 부른다.
- `/api/me` 아래 요청이 401이면 `refresh`를 한 번 부른 뒤 다시 요청한다. `refresh`도 401이면 로그아웃 상태로 본다.
- 결과 쿼리(`login`, `withdrawn`, `withdraw`)는 안내를 보여 준 뒤 주소에서 지운다.
