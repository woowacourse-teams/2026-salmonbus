# 카카오 로컬 로그인

로컬 프론트엔드(`http://localhost:3000`)와 로컬 백엔드(`http://localhost:8080`)로 카카오 로그인을 완료하는 설정이다. Apple 웹 로그인은 이 localhost 설정에서 지원하지 않는다.

## 받아올 값

백엔드 담당자 또는 카카오 개발앱 관리자에게 같은 **개발앱**의 아래 세 값을 받는다.

| 환경변수                   | 받아올 값                                                                  |
| -------------------------- | -------------------------------------------------------------------------- |
| `AUTH_KAKAO_CLIENT_ID`     | REST API 키 전체 값. 숫자 앱 ID, Native 앱 키, JavaScript 키가 아니다      |
| `AUTH_KAKAO_CLIENT_SECRET` | 카카오 로그인 Client Secret                                                |
| `AUTH_KAKAO_ADMIN_KEY`     | Admin Key. 탈퇴 시 연결 해제용이며, 현재 백엔드는 이 값도 기동 시 검증한다 |

같은 개발앱의 카카오 로그인 Redirect URI에 아래 주소가 등록되어 있어야 한다.

```text
http://localhost:3000/api/auth/kakao/callback
```

## 파일 준비

명령은 `frontend/`에서 실행한다. 실제 키는 `env/.env.kakao-local`에만 넣는다. 이 파일은 Git에서 제외된다.

```sh
cp -n env/.env.kakao-local.example env/.env.kakao-local
chmod 600 env/.env.kakao-local
```

파일의 카카오 값 세 개를 채운다. `AUTH_TOKEN_SECRET`과 `AUTH_REFRESH_TOKEN_SECRET`은 서로 다른 로컬 전용 값으로 생성한다. 각 값은 `openssl rand -hex 32`로 생성한 64자리 문자열을 사용하며, 결과는 파일에 직접 넣고 공유하지 않는다. 운영 서버의 DB 비밀번호와 토큰 서명키는 필요하지 않다.

이미 생성된 로컬 서명키가 있다면 그대로 사용한다. 바꾸면 로컬에서 발급한 로그인 상태가 무효가 된다.

## 백엔드·DB·예보 worker 실행

Docker Desktop을 실행한 뒤 아래 명령을 실행한다. 기존 로컬 Compose에 카카오 환경변수를 덮어쓰되, `salmonbus-kakao-local` 전용 프로젝트와 볼륨을 사용한다. 기존 로컬 DB의 데이터를 변경하지 않고 새 DB에서 마이그레이션한다. API·PostgreSQL·MongoDB와 함께 예보 worker와 모의 GBIS를 시작하고, 첫 예보가 준비될 때까지 기다린다. 별도 프론트엔드 컨테이너는 시작하지 않는다.

```sh
docker compose --project-directory .. --env-file env/.env.kakao-local \
  -f ../compose.local.yaml -f compose.kakao-local.yaml \
  up --build -d --wait --wait-timeout 180 worker

docker compose --project-directory .. --env-file env/.env.kakao-local \
  -f ../compose.local.yaml -f compose.kakao-local.yaml \
  run --rm --no-deps local-ready
```

키가 빠져 있으면 실행 전에 어떤 값을 채워야 하는지 알려주며 중단한다. DB 비밀번호는 기존 로컬 설정 초기화가 자동 생성한다. 서버 서명키와 카카오 비밀은 백엔드 컨테이너에만 전달한다.

worker는 의존 서비스인 API와 DB 초기화를 함께 시작하고 모델을 활성화한 뒤 차량 관측과 예보를 생성한다. API와 DB만 실행하면 로그인과 노선 목록은 동작해도 판정보드는 `503 MODEL_OUT_OF_SCOPE`로 응답할 수 있다. `local-ready`가 첫 예보 준비를 확인한 뒤 프론트를 시작한다.

## 프론트엔드 연결

백엔드가 준비된 뒤 `env/.env.development`의 값을 다음과 같이 바꾸고 개발 서버를 재시작한다.

```dotenv
API_PROXY_TARGET=http://localhost:8080
```

```sh
pnpm dev
```

`http://localhost:3000/api/auth/session`이 JSON 200을 반환하는지 확인한 뒤 카카오 로그인 버튼을 누른다. 시작과 콜백을 모두 localhost:3000에서 받고, `/api` 요청은 개발 서버가 로컬 백엔드로 전달한다. 백엔드의 최종 복귀 주소도 같은 localhost:3000을 기준으로 열리므로 로그인 쿠키와 원래 화면 복귀가 유지된다.

공유 dev·운영 서버의 설정은 바꾸지 않는다. 실제 카카오 계정의 로그인 성공은 로컬 비밀 설정을 채우고 기동한 뒤 별도로 검증한다.

## 기동과 검증 결과 (2026-10-22)

카카오 전용 프로젝트에서 API와 PostgreSQL·MongoDB를 정상 기동했고, 41개 노선의 초기 데이터 준비가 완료됐다. 기존 로컬 DB에는 V39 데이터 전환 승인이 필요한 데이터가 있어, 이 DB를 변경하지 않고 전용 프로젝트의 새 볼륨을 사용했다.

실제 로컬 API로 다음을 확인했다.

- `GET /api/auth/session`이 JSON 200을 반환하고 CSRF 쿠키를 발급한다.
- 카카오 로그인 시작 응답의 `redirect_uri`는 `http://localhost:3000/api/auth/kakao/callback`이고, 카카오 계정 로그인 페이지로 연결된다.
- Chrome·Firefox에서 실제 임시 세션 쿠키와 합성 취소 콜백을 사용해 localhost로 복귀하고, 원래 `/mypage` 경로를 보존한다.
- 개발 모드에서 같은 복귀 결과를 두 번 소비하던 문제를 수정했다. 홈·마이페이지 복귀와 로그인·취소·탈퇴의 모의 응답 E2E 45개가 세 브라우저에서 통과했다.
- worker·모의 GBIS 기동과 `local-ready` 성공 후, 로컬 API와 프론트 프록시 모두에서 3330·1650의 판정보드·차량 API가 200으로 응답했다. 세션 API의 JSON 200과 CSRF 쿠키 발급도 유지됐다.

Playwright WebKit은 HTTP localhost에서 Secure 쿠키를 저장하지 않아 실제 로컬 쿠키 검증을 통과하지 못했다. HTTP 로컬 로그인 확인에는 Chrome·Dia 또는 Firefox를 사용한다. Secure 속성과 `__Host-` 쿠키 이름은 유지하며, Safari 계열의 실제 로그인 확인에는 별도의 HTTPS 콜백 환경이 필요하다.

사용자가 실제 카카오 계정으로 인증한 뒤 `localhost:3000`의 마이페이지로 복귀한 것을 확인했다. 로컬 DB에도 KAKAO 회원 1명과 리프레시 토큰 계통 1개가 생성됐다. 계정 식별자와 토큰 값은 출력하지 않았다. 실제 회원의 닉네임 변경·탈퇴와 장기 토큰 갱신은 아직 확인하지 않았다.
