import { execFileSync, spawn } from "node:child_process";
import { appendFileSync, closeSync, existsSync, openSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { homedir } from "node:os";
import path from "node:path";
import process from "node:process";
import { testLabelOf, type FailedTest } from "./failures.ts";
import type { Agent, Verdict } from "./state.ts";
import { configFile, frontendDir, healerPort, worktreeDir } from "./worktree.ts";

export const codexHome = path.join(homedir(), ".codex-e2e-healer");

const healerTimeoutMs = 30 * 60_000;

export interface HealerExit {
  exitCode: number | null;
  timedOut: boolean;
}
const verdictsHeaviestFirst: Verdict[] = ["제품 버그 의심", "판단 못 함", "테스트 수정"];

const mcpServer = {
  command: "npx",
  args: ["playwright", "run-test-mcp-server", "--headless", "-c", configFile],
};

// 에이전트 세션 안에서 푸시하면 그 세션의 정보가 환경 변수로 넘어온다. healer는 따로 시작한 세션으로 돌린다.
const parentSessionVariable =
  /^(CLAUDECODE|CLAUDE_PID|CLAUDE_EFFORT|CLAUDE_CODE_(ENTRYPOINT|EXECPATH|SESSION_.*|CHILD_SESSION|MESSAGING_.*))$/;

function healerEnvFor(agent: Agent) {
  const env = Object.fromEntries(Object.entries(process.env).filter(([name]) => !parentSessionVariable.test(name)));
  return agent === "codex" ? { ...env, CODEX_HOME: codexHome } : env;
}

function spawnLogged(command: string, args: string[], options: { env: NodeJS.ProcessEnv; output: string }) {
  const output = openSync(options.output, "a");
  return new Promise<HealerExit>((resolve, reject) => {
    // 프로세스 그룹을 따로 만들어 끝난 뒤 남은 MCP 서버와 웹 서버까지 정리한다.
    const child = spawn(command, args, {
      cwd: frontendDir,
      env: options.env,
      stdio: ["ignore", output, output],
      detached: true,
    });
    const stopGroup = () => {
      if (!child.pid) return;
      try {
        process.kill(-child.pid, "SIGTERM");
      } catch {
        // 이미 모두 끝났다.
      }
    };
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      stopGroup();
    }, healerTimeoutMs);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("exit", (exitCode) => {
      clearTimeout(timer);
      stopGroup();
      resolve({ exitCode, timedOut });
    });
  }).finally(() => closeSync(output));
}

function isPortFree(port: number) {
  return new Promise<boolean>((resolve) => {
    const server = createServer();
    server.once("error", () => resolve(false));
    server.listen(port, "127.0.0.1", () => server.close(() => resolve(true)));
  });
}

// 부를 수 있으면 null을 돌려준다.
export async function whyHealerCannotRun(agent: Agent) {
  try {
    execFileSync(agent, agent === "codex" ? ["login", "status"] : ["--version"], {
      env: healerEnvFor(agent),
      stdio: "ignore",
    });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return `${agent} 명령을 찾지 못했어요.`;
    if (agent === "codex") {
      return "Codex 로그인이 필요해요: mkdir -p ~/.codex-e2e-healer && CODEX_HOME=~/.codex-e2e-healer codex login";
    }
    return `${agent} --version이 실패했어요.`;
  }
  if (!(await isPortFree(healerPort))) return `${healerPort} 포트를 다른 프로그램이 쓰고 있어요.`;
  return null;
}

export function promptFor(agent: Agent, failures: FailedTest[], reportDir: string) {
  const request =
    agent === "codex"
      ? "playwright_test_healer 에이전트로 실패한 테스트를 고쳐 주세요."
      : "실패한 테스트를 고쳐 주세요.";
  const tests = failures.map((test) => {
    const errorContext = test.errorContext ? `\n  CI 첫 실패 기록: ${path.join(reportDir, test.errorContext)}` : "";
    return `- ${testLabelOf(test)}${errorContext}`;
  });
  return [
    `CI에서 E2E가 실패했어요. ${request}`,
    "",
    "실패한 테스트",
    ...tests,
    "",
    `CI의 trace와 스크린샷은 ${reportDir} 아래에 있어요.`,
    "로컬 재현은 `test_run` 도구로 해 주세요.",
  ].join("\n");
}

function claudeArgsFor(prompt: string, artifactDir: string) {
  return [
    "-p",
    prompt,
    "--agent",
    "playwright-test-healer",
    // 개인 설정과 메모리, 전역 CLAUDE.md가 healer에 섞이지 않게 한다.
    "--setting-sources",
    "project",
    "--settings",
    JSON.stringify({ autoMemoryEnabled: false, claudeMdExcludes: [`${homedir()}/.claude/**`] }),
    "--strict-mcp-config",
    "--mcp-config",
    JSON.stringify({ mcpServers: { "playwright-test": mcpServer } }),
    "--add-dir",
    artifactDir,
    "--permission-mode",
    "acceptEdits",
    "--permission-prompts",
    "none",
    "--output-format",
    "stream-json",
    "--verbose",
    "--no-session-persistence",
    "--allowedTools",
    "mcp__playwright-test",
    "Bash(git diff:*)",
    "Bash(git log:*)",
    "Bash(git status:*)",
    "Bash(git show:*)",
    "Bash(git ls-files:*)",
  ];
}

function codexArgsFor(prompt: string, lastMessageFile: string) {
  return [
    "exec",
    "--json",
    "-s",
    "workspace-write",
    "-o",
    lastMessageFile,
    "-c",
    `mcp_servers.playwright-test.command=${JSON.stringify(mcpServer.command)}`,
    "-c",
    `mcp_servers.playwright-test.args=${JSON.stringify(mcpServer.args)}`,
    prompt,
  ];
}

function trustWorktreeForCodex() {
  const configPath = path.join(codexHome, "config.toml");
  const table = `[projects."${worktreeDir}"]`;
  const config = existsSync(configPath) ? readFileSync(configPath, "utf8") : "";
  if (!config.includes(table)) appendFileSync(configPath, `\n${table}\ntrust_level = "trusted"\n`);
}

function claudeResultFrom(output: string) {
  const results = output.split("\n").flatMap((line) => {
    try {
      const event = JSON.parse(line);
      return event.type === "result" && typeof event.result === "string" ? [event.result as string] : [];
    } catch {
      return [];
    }
  });
  return results.at(-1) ?? "";
}

export async function runHealer(agent: Agent, prompt: string, artifactDir: string, runDir: string) {
  const output = path.join(runDir, "healer-output.jsonl");
  const lastMessageFile = path.join(runDir, "healer-last-message.txt");
  writeFileSync(path.join(runDir, "prompt.txt"), prompt + "\n");
  if (agent === "codex") trustWorktreeForCodex();

  const startedAt = Date.now();
  const exit = await spawnLogged(
    agent,
    agent === "claude" ? claudeArgsFor(prompt, artifactDir) : codexArgsFor(prompt, lastMessageFile),
    { env: healerEnvFor(agent), output },
  );
  const finalMessage =
    agent === "claude"
      ? claudeResultFrom(readFileSync(output, "utf8"))
      : existsSync(lastMessageFile)
        ? readFileSync(lastMessageFile, "utf8")
        : "";
  return { ...exit, finalMessage, seconds: Math.round((Date.now() - startedAt) / 1000) };
}

function verdictsFrom(report: string): Verdict[] {
  const plain = report.replaceAll(/[*`]/g, "");
  const found = new Set(
    [...plain.matchAll(/판정\s*:\s*(테스트 수정|제품 버그 의심|판단 못 함)(?!\s*\|)/g)].map((match) => match[1]),
  );
  return verdictsHeaviestFirst.filter((verdict) => found.has(verdict));
}

export type HealerResult = { verdicts: [Verdict, ...Verdict[]] } | { failure: string };

export function healerResultFor(exit: HealerExit, report: string): HealerResult {
  if (exit.timedOut) return { failure: "healer가 30분 안에 끝나지 않아 멈췄어요." };
  if (exit.exitCode !== 0) return { failure: `healer가 종료 코드 ${exit.exitCode}로 끝났어요.` };
  const [heaviest, ...rest] = verdictsFrom(report);
  return heaviest ? { verdicts: [heaviest, ...rest] } : { failure: "healer 보고에서 판정을 찾지 못했어요." };
}

export function warningsFor(foundVerdicts: Verdict[], changedFiles: string[]) {
  const warnings: string[] = [];
  const outsideE2e = changedFiles.filter((file) => !file.startsWith("frontend/e2e/"));
  if (outsideE2e.length > 0) warnings.push(`e2e 밖의 파일을 바꿨어요: ${outsideE2e.join(", ")}`);
  if (changedFiles.length === 0) return warnings;
  if (!foundVerdicts.includes("테스트 수정")) {
    warnings.push(`판정이 ${foundVerdicts.join(", ")}인데 바뀐 파일이 있어요.`);
  } else if (foundVerdicts.length > 1) {
    warnings.push("판정이 섞여 있어요. 바뀐 파일을 가져오기 전에 테스트마다 판정을 확인해 주세요.");
  }
  return warnings;
}
