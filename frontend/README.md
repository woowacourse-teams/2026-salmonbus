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

## 푸시한 뒤 E2E 실패 지켜보기

푸시하면 `pre-push` hook이 그 커밋의 FRONTEND-CI 실행을 백그라운드에서 지켜봅니다. E2E가 실패하면 CI가 올린 실패 기록(`playwright-results`)을 받아 healer를 부르고, 판정을 macOS 알림으로 보여 줍니다. healer가 지키는 규칙은 [AGENTS.md](AGENTS.md)에 있습니다.

### 처음 한 번 설정

리포 루트에서 실행합니다.

1. hook 켜기: `git config core.hooksPath frontend/.githooks`
2. healer 고르기: `git config salmonbus.e2eHealer claude` (또는 `codex`, `off`). 정하지 않으면 `claude`입니다.
3. Codex를 고른 사람만: `mkdir -p ~/.codex-e2e-healer`를 먼저 실행한 뒤 `CODEX_HOME=~/.codex-e2e-healer codex login`. 폴더가 없으면 Codex가 `CODEX_HOME points to ... but that path does not exist`로 멈춥니다. 평소 Codex 설정의 개인 지시(예: git 명령 금지)가 healer에 섞이면 healer가 git으로 원인을 확인하지 못해 `판단 못 함`으로 끝나서, healer 전용 폴더에 따로 로그인합니다.
4. 끄기: `git config --unset core.hooksPath` 또는 `git config salmonbus.e2eHealer off`

Codex를 골랐는데 로그인하지 않았으면 healer를 부르지 않고 `E2E: 실패 N개, healer를 부르지 못함` 알림에 `Codex 로그인이 필요해요: mkdir -p ~/.codex-e2e-healer && CODEX_HOME=~/.codex-e2e-healer codex login`이 뜹니다.

`core.hooksPath`는 이 clone의 모든 워크트리에 적용됩니다. hook은 각 워크트리에 체크아웃된 `frontend/.githooks/pre-push`를 쓰므로, 이 파일이 없는 브랜치에서는 아무것도 하지 않습니다. hook은 감시를 띄우고 바로 끝나서 푸시를 기다리게 하지 않습니다. 감시와 healer는 `node`, `pnpm`, `gh`를 쓰므로 VS Code에서 푸시할 때도 이 명령들이 PATH에 있어야 합니다.

### 무엇을 하나

1. 푸시한 브랜치와 커밋으로 FRONTEND-CI 실행을 찾습니다. 같은 커밋을 dev push가 이미 돌렸어도 그 실행을 이 브랜치 실행으로 잡지 않습니다. PR이 없거나 paths 필터에 걸려 10분 동안 실행이 생기지 않으면 조용히 끝납니다.
2. 실행이 끝날 때까지 기다립니다. 성공한 실행과 새 푸시에 밀려 취소된 실행은 알리지 않습니다. job 시간 제한에 걸린 실행은 GitHub에서 `cancelled`로 끝나지만 `E2E: CI 시간 초과`로 알리고, 그 밖의 이유로 끝난 실행도 알립니다.
3. 실패하면 실패 기록에서 실패한 테스트를 읽습니다. 재시도로 통과한 테스트는 실패로 세지 않습니다. 실패한 테스트가 없으면 `E2E: 실패 없음, 다른 CI 단계 실패`로 알립니다.
4. 실패한 테스트 가운데 dev의 가장 최근 push 실행에서도 실패한 테스트는 healer에게 넘기지 않고 `dev 쪽 문제`로 따로 알리며, 이 브랜치에서만 실패한 테스트만 healer에게 넘깁니다. 모든 실패가 dev에서도 났으면 healer를 부르지 않습니다.
5. 푸시한 커밋으로 임시 워크트리(`$TMPDIR/salmonbus-ci-watch`)를 만들고, 그 안에서 healer가 넘겨받은 실패의 프로젝트와 파일만 5593 포트로 다시 돌립니다. 작업 중인 폴더는 건드리지 않습니다.
6. 판정(`테스트 수정`, `제품 버그 의심`, `판단 못 함`)과 바꾼 파일을 알리고 임시 워크트리를 지웁니다. 테스트마다 판정이 다르면 무거운 판정부터 모두 알립니다. healer는 커밋하지 않습니다.

healer는 한 번에 하나만 돌고, 한 실행에 한 번만 돕니다. healer가 판정 없이 끝나면(종료 코드가 0이 아님, 30분 초과, 보고에 판정 없음) 결과를 저장하지 않아서, 원인을 고친 뒤 같은 run id로 다시 돌릴 수 있습니다. Codex를 고르면 임시 워크트리를 신뢰하는 설정 한 줄이 `~/.codex-e2e-healer/config.toml`에 더해집니다.

### 결과 보기

알림에 나온 명령으로 판정, 실패한 테스트, healer 보고를 봅니다. 이미 판정한 실행이면 healer를 다시 부르지 않고 앞의 판정을 보여 줍니다. 아직 판정하지 않은 실행이면 지금 판정합니다. 저장된 결과가 지금 형식과 맞지 않으면 어느 값이 틀렸는지와 다시 판정하는 방법을 알리고 멈춥니다.

```sh
pnpm ci:watch --run <run id>
```

- 기록은 `~/.local/state/salmonbus-ci-watch/<run id>/`에 남습니다(`XDG_STATE_HOME`을 정했으면 `$XDG_STATE_HOME/salmonbus-ci-watch/<run id>/`). `summary.md`(요약), `healer.diff`(healer가 바꾼 내용), `healer-output.jsonl`(healer 원문), `artifact/`(CI 실패 기록, `npx playwright show-report <폴더>/artifact/playwright-report`로 엽니다)입니다. hook으로 돈 감시의 로그는 `salmonbus-ci-watch/logs/<커밋>.log`에 있고, node가 감시를 시작하지 못했을 때의 오류도 같은 파일에 남습니다. 실패한 실행마다 20MB 안팎이 쌓이니 필요 없어진 폴더는 지워도 됩니다.
- healer 정의가 들어오기 전에 갈라진 브랜치라 커밋에 정의가 없으면 `pnpm ci:watch --run <run id> --healer-from <정의가 있는 frontend 폴더>`로 그 폴더의 정의를 빌려 씁니다. 커밋에 정의가 있으면 커밋의 정의를 씁니다.
- healer가 테스트를 고쳤으면 `summary.md`에 나온 `git apply` 명령을 리포 루트에서 실행해 가져온 뒤, 리뷰하고 커밋합니다.
- Orca를 쓰면 푸시한 워크트리의 카드 문구가 `E2E:`로 시작하는 판정으로 바뀌고 읽지 않음 표시가 켜집니다. 바뀌기 전 문구는 그 실행 폴더의 `orca-previous-comment.txt`에 남습니다. 바뀌기 전 문구가 감시가 쓴 `E2E:` 판정이면 남기지 않으므로, 이 파일에는 감시가 덮어쓴 원래 문구만 남습니다. Orca를 쓰지 않거나 Orca에 없는 워크트리면 아무 일도 일어나지 않습니다.

## E2E 초안을 planner와 generator로 만들기

Playwright Test Agents의 planner는 앱을 둘러보고 테스트 계획을 쓰고, generator는 계획의 단계를 브라우저에서 직접 해 본 기록으로 테스트 초안을 씁니다. 초안은 사람이 고쳐서 올립니다.

- healer처럼 `frontend/`에서 실행해야 보입니다.
  - Claude Code: `.claude/agents/playwright-test-planner.md`, `.claude/agents/playwright-test-generator.md`
  - Codex: `.codex/agents/playwright_test_planner.toml`, `.codex/agents/playwright_test_generator.toml`
  - MCP 서버 설정과 처음 실행할 때의 허용은 healer와 같습니다.
- 두 에이전트는 seed 테스트 `e2e/seed.spec.ts`를 끝까지 돌린 페이지에서 시작합니다. seed는 `e2e/fixtures/api.ts`의 `test`를 써서 API 목과 지도 SDK 차단이 걸린 채로 홈을 열고, 끝에서 `page.clock.resume()`으로 시계를 다시 흐르게 합니다. seed는 CI에서도 돌아서 깨지면 바로 보입니다.
- seed에서 시계를 다시 흐르게 하는 이유: 에이전트의 브라우저 도구는 클릭 같은 동작이 끝나기를 페이지 안의 `setTimeout`으로 기다립니다. fixture가 멈춘 시계 그대로 두면 이 타이머가 끝나지 않아 첫 동작부터 응답하지 않습니다. 새로 만든 테스트는 여전히 멈춘 시계로 시작하므로, 시간이 지나야 하는 단계는 `page.clock`으로 넘깁니다.
- 브라우저 도구로는 `api.mode`를 바꾸거나 테스트의 `page.clock` 진행을 기록할 수 없습니다. 서버 오류나 시간 경과가 필요한 단계는 generator가 `TODO:` 주석으로 남기고, 사람이 채웁니다.
- 처음 화면부터 특정 응답이 필요하면(예: 판정 보드 첫 조회가 503) `e2e/seed.spec.ts`를 복사해 `api.mode`를 바꾼 seed를 만들고, 계획의 `**Seed:**`에 그 경로를 적습니다. 다 쓴 seed는 지웁니다.
- 부르는 예시
  - Claude Code: `playwright-test-generator 에이전트로 아래 계획의 테스트를 만들어 줘` 다음에 계획(시나리오 이름, `**Seed:**` 경로, 단계)을 붙입니다.
  - Codex: `playwright_test_generator 에이전트로 아래 계획의 테스트를 만들어 줘`
  - planner: `playwright-test-planner 에이전트로 판정 보드 테스트 계획을 세워 줘`. 계획 파일은 커밋하지 않습니다.
- 초안을 올리기 전에 사람이 할 일: 테스트를 둘 spec 파일로 옮기기, `e2e/utils/`의 함수로 바꾸기, `TODO:` 단계(목 설정, 시계 진행) 채우기, 기존 테스트가 이미 확인하는 내용 빼기, `pnpm lint`와 `pnpm typecheck` 통과.
- 4173 포트가 이미 쓰이고 있으면 포트만 바꾼 Playwright 설정을 만들고 MCP 서버를 `npx playwright run-test-mcp-server -c <그 설정>`으로 띄웁니다. Claude Code는 `--mcp-config`로, Codex는 `-c 'mcp_servers.playwright-test.args=[...]'`로 커밋된 설정 대신 쓸 수 있습니다.
- `npx playwright init-agents`를 다시 돌린 뒤에는 위 healer 절의 복구와 함께 다음을 합니다.
  - 네 정의 파일의 `# Rules for this repository` 절을 되살립니다.
  - 두 generator 정의의 예시 테스트에 `import { test, expect } from './fixtures/api';` 줄과 `async ({ page }) =>` 콜백을 되살립니다. 생성본 예시는 import가 없고 `async { page } =>`라서 문법이 틀립니다.
  - Codex 정의에 다시 생긴 `[mcp_servers.playwright-test]`와 `enabled_tools`를 지웁니다.
  - 새로 생긴 `specs/README.md`를 지웁니다. `e2e/seed.spec.ts`는 이름에 seed가 든 파일이 이미 있어서 새로 만들어지지 않습니다.
  - `pnpm exec prettier --write .claude/agents`로 형식을 맞춥니다.
