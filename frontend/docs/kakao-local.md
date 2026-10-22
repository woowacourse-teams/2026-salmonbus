# 카카오 로컬 로그인

로컬 프론트엔드(`http://localhost:3000`)와 로컬 백엔드(`http://localhost:8080`)로 카카오 로그인을 완료하는 설정이다. Apple 웹 로그인은 이 localhost 설정에서 지원하지 않는다.

## 받아올 값

백엔드 담당자 또는 카카오 개발앱 관리자에게 같은 **개발앱**의 아래 세 값을 받는다.

| 환경변수                   | 받아올 값                                                                  |
| -------------------------- | -------------------------------------------------------------------------- |
| `AUTH_KAKAO_CLIENT_ID`     | REST API 키. Native 앱 키와 JavaScript 키가 아니다                         |
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

## 백엔드와 DB 실행

Docker Desktop을 실행한 뒤 아래 명령을 실행한다. 기존 로컬 Compose에 카카오 환경변수만 덮어쓴다. API와 로컬 DB, MongoDB를 시작하고 개발 데이터를 준비하며, 별도 프론트엔드 컨테이너는 시작하지 않는다.

```sh
docker compose --project-directory .. --env-file env/.env.kakao-local \
  -f ../compose.local.yaml -f compose.kakao-local.yaml \
  up --build -d api local-init
```

키가 빠져 있으면 실행 전에 어떤 값을 채워야 하는지 알려주며 중단한다. DB 비밀번호는 기존 로컬 설정 초기화가 자동 생성한다. 서버 서명키와 카카오 비밀은 백엔드 컨테이너에만 전달한다.

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
