# 프런트엔드 환경변수

카카오 로그인을 localhost에서 완료하려면 [카카오 로컬 로그인 설정](docs/kakao-local.md)을 따른다. 로컬 백엔드의 비밀 설정은 `env/.env.kakao-local`에 넣고, 공개 프론트엔드 환경변수와 분리한다.

## 배포된 dev API로 로컬 실행

`env/.env.development`에 `API_PROXY_TARGET=https://dev.salmonbus.com`을 설정합니다. 이 값은 webpack 개발 서버를 시작할 때 읽으므로, 변경한 뒤 `pnpm dev`를 다시 실행해야 합니다. 값을 비우면 로컬 백엔드(`http://localhost:8080`)로 요청합니다.

로컬 서버가 다른 API를 가리키면 dev 백엔드가 배포돼 있어도 로그인 버튼이 비활성화될 수 있습니다. 로컬 `/api/auth/session`이 JSON 200을 반환하는지 먼저 확인합니다. 실제 소셜 인증의 콜백은 서버에 등록된 dev origin으로 돌아오므로, 로그인 왕복은 같은 dev origin에서 검증합니다.

## E2E 서버

`pnpm test:e2e`는 env 파일을 읽지 않는 운영 빌드를 만든 뒤 `dist`를 정적 서버(`e2e/static-server.ts`)로 띄워 테스트합니다. 개인 `env/.env.development`, `env/.env.production`의 값은 E2E 번들에 들어가지 않습니다. 정적 서버는 `/api` 요청에 404를 돌려주고, 그 밖의 경로에는 `index.html`을 돌려줍니다.

개발 서버로 확인하려면 `pnpm test:e2e:dev`를 실행합니다. 두 명령 모두 4173 포트를 쓰고, 이 포트에 다른 서버가 떠 있으면 그 서버를 재사용하지 않고 시작 전에 멈춥니다.

## 지도 공개 키 ID

네이버 Maps Web Dynamic Map의 공개 키 ID를 `WEBPACK_NAVER_MAP_KEY_ID`로 전달합니다. 이 값은 브라우저 번들에 포함되는 공개 식별자이며, NAVER Cloud의 애플리케이션에 등록한 Web 서비스 URL에서 사용합니다.

- 로컬 개발: `env/.env.example`을 참고해 `env/.env.development`에 설정하고 `pnpm dev`를 실행합니다.
- dev 배포: dev 배포를 담당하는 **CodeBuild 프로젝트 환경변수**에 이름 `WEBPACK_NAVER_MAP_KEY_ID`와 해당 애플리케이션의 키 ID를 설정합니다. 프로젝트에서 Parameter Store를 사용하는 경우에도 빌드 프로세스에 이 이름으로 값이 전달되어야 합니다.
- 운영에서 지도를 사용할 때도 운영 CodeBuild 프로젝트에 같은 이름으로 키 ID를 설정합니다.
- NAVER Cloud의 애플리케이션에는 실제 사용하는 로컬 주소와 배포 주소를 Web 서비스 URL로 등록합니다. dev 주소는 `https://dev.salmonbus.com`입니다.

CodeBuild는 `frontend/buildspec.yml`의 `pnpm build`로 정적 파일을 만듭니다. webpack이 빌드할 때 키 ID를 `__NAVER_MAP_KEY_ID__`로 치환하므로, 환경변수만 수정해서는 이미 배포된 파일이 바뀌지 않습니다. **환경변수를 설정한 뒤 다시 빌드·배포해야 합니다.**

`WEBPACK_SENTRY_ENVIRONMENT=development`인 dev 배포의 preflight는 키 ID가 없거나 공백뿐이면 중단합니다. 일반 `pnpm build`와 운영 배포에는 이 검사를 적용하지 않습니다. 다만 키 ID가 없는 산출물로는 네이버 지도를 초기화할 수 없습니다. 키 ID를 저장소에 직접 적거나 빌드 로그에 출력하지 않습니다.

지도 데이터와 공개 키 ID는 각각 필요합니다. 키 ID가 있어도 노선 좌표·경로 데이터가 없으면 지도가 만들어지지 않으며, 화면에는 “지도 정보를 준비 중이에요”가 표시됩니다. 노선 데이터가 준비된 뒤 키 ID가 비어 있으면 “지도 설정을 준비 중이에요”가 표시됩니다.

## 지도 데이터

판정보드의 노선 ID와 기준 정보 판본으로 `/api/v2/routes/:routeId/geometry?referenceVersionId=...`를 조회합니다. 같은 판본의 보드가 갱신돼도 geometry를 다시 요청하지 않으며, 판본이 바뀌면 이전 요청을 취소합니다.

도로 경로가 아직 수집되지 않았어도 유효한 정류장 좌표가 있으면 기본 지도와 정류장을 표시합니다. 전체 도로 형상(`points`)은 있지만 방향별 경로가 없으면 전체 선을 중립 색상으로 표시합니다. 방향별 경로가 없는 버스는 최근 응답의 정류장 좌표에 표시하며, 정류장 사이를 직선으로 보간하거나 전체 형상에 임의의 방향을 부여하지 않습니다. 전체 형상도 비어 있으면 경로 선은 표시하지 않습니다. 지도 조회 실패나 판본 불일치 시에도 정류장 목록과 방향 선택은 계속 사용할 수 있습니다.
