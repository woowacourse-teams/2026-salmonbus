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

## 실패한 E2E를 healer로 고치기

Playwright Test Agents의 healer가 실패한 E2E를 로컬에서 다시 돌려 봅니다. 의도한 화면 변경이면 테스트를 고치고, 제품 버그로 보이면 테스트를 고치지 않고 보고합니다. healer와 healer를 부른 세션이 따르는 규칙은 [AGENTS.md](AGENTS.md)에 있습니다.

- Claude Code와 Codex 모두 `frontend/`에서 실행해야 healer가 보입니다. 리포 루트에서 실행하면 보이지 않습니다.
  - Claude Code: healer 정의 `.claude/agents/playwright-test-healer.md`, MCP 서버 설정 `.mcp.json`, 규칙을 불러오는 `CLAUDE.md`
  - `CLAUDE.md`의 `@AGENTS.md` 줄은 지우지 않습니다. Claude Code는 이 폴더나 상위 폴더에 `CLAUDE.md`나 `CLAUDE.local.md`가 있으면 `AGENTS.md`를 스스로 읽지 않아서, 이 줄로 규칙을 불러옵니다.
  - Codex: healer 정의 `.codex/agents/playwright_test_healer.toml`, MCP 서버 설정 `.codex/config.toml`
- 처음 실행할 때 허용이 필요합니다.
  - Claude Code: 시작할 때 `.mcp.json`의 `playwright-test` MCP 서버를 허용합니다.
  - Codex: 이 폴더를 신뢰한다고 답합니다. 신뢰하지 않으면 `.codex/agents/`를 읽지 않아 healer가 목록에 없습니다.
- healer도 위 "E2E 서버" 설정으로 테스트를 돌립니다. 4173에 다른 서버가 떠 있으면 healer의 테스트도 시작 전에 멈추니, 그 서버를 끄고 다시 부릅니다.
- healer는 `origin/dev`와 비교해 이 브랜치가 바꾼 것을 찾습니다. 부르기 전에 `git fetch origin dev`로 비교 기준을 최신으로 맞춥니다.
- 부르는 예시
  - Claude Code: `playwright-test-healer 에이전트로 e2e/station-arrivals.spec.ts 실패를 고쳐 줘`. healer만 바로 쓰려면 `claude --agent playwright-test-healer`로 시작합니다.
  - Codex: `playwright_test_healer 에이전트로 e2e/station-arrivals.spec.ts 실패를 고쳐 줘`
  - 실패한 파일과 줄, 프로젝트를 같이 알려 주면(예: `e2e/board-map.spec.ts:645 (chromium)`) healer가 그 테스트만 돌립니다. 파일만 알려 주면 그 파일만 돌리고, 파일도 알려 주지 않으면 돌리지 않고 실패한 파일과 줄을 알려 달라고 보고합니다.
- healer는 마지막에 판정을 `테스트 수정`, `제품 버그 의심`, `판단 못 함` 중 하나로 보고합니다. `제품 버그 의심`과 `판단 못 함`이면 테스트는 그대로 두고 보고에 적힌 원인이나 근거를 확인합니다.
- 개인 전역 지시(예: `~/.codex/AGENTS.md`, `~/.claude/CLAUDE.md`)가 git 명령을 막으면 healer는 `AGENTS.md` 5번의 git 확인을 하지 못합니다. 이때 healer는 8번에 따라 테스트를 고치지 않고 `판단 못 함`으로 끝납니다.
- healer는 커밋하지 않습니다. 고친 테스트는 사람이 diff를 리뷰하고 커밋합니다.
- `npx playwright init-agents`를 다시 돌리면 두 healer 정의가 생성본으로 덮입니다. 다시 돌린 뒤에는 diff를 보고 `AGENTS.md`를 가리키는 문장, 실패한 테스트만 돌리라는 workflow 1번, Claude Code 정의의 `Bash` 도구를 되살리고, Codex 정의에 다시 생긴 `mcp_servers`는 지웁니다. Codex는 에이전트 파일의 `mcp_servers`를 쓰지 않아서 MCP 서버는 `.codex/config.toml`에 둡니다.
