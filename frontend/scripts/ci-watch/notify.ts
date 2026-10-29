import { execFileSync } from "node:child_process";
import path from "node:path";
import { testLabelOf } from "./failures.ts";
import { runDirFor, type DevOverlap, type HealerReport, type WatchResult } from "./state.ts";

export interface Notice {
  title: string;
  subtitle: string;
  message: string;
}

// Orca 카드 문구로도 쓴다. 판정에서 온 문구인 것이 보이게 E2E:로 시작한다.
export function titleFor(result: WatchResult) {
  const failures = `실패 ${result.failures.length}개`;
  switch (result.outcome) {
    case "not-e2e":
      return "E2E: 실패 없음, 다른 CI 단계 실패";
    case "dev-problem":
      return `E2E: ${failures}, dev 쪽 문제`;
    case "healed": {
      const verdicts = `E2E: ${failures}, ${result.healer.verdicts.join(", ")}`;
      return result.devOverlap ? `${verdicts}, dev 쪽 문제 ${result.devOverlap.sharedFailures.length}개` : verdicts;
    }
  }
}

function healerLeadFor({ warnings, changedFiles }: HealerReport) {
  const [warning] = warnings;
  if (warning) return `확인 필요: ${warning}`;
  const files = changedFiles.map((file) => file.replace(/^frontend\//, ""));
  return files.length > 0 ? `${files.join(", ")}를 고쳤어요.` : "테스트는 그대로예요.";
}

function leadFor(result: WatchResult) {
  if (result.outcome === "dev-problem") return `dev push에서도 ${result.sharedFailures.length}개가 실패해요.`;
  if (result.outcome === "not-e2e") return "lint, 타입 검사, 빌드 같은 다른 단계를 확인해 주세요.";

  const lead = healerLeadFor(result.healer);
  if (!result.devOverlap) return lead;
  return `${lead} dev push에서도 실패한 ${result.devOverlap.sharedFailures.length}개는 healer에게 넘기지 않았어요.`;
}

export function noticeFor(result: WatchResult): Notice {
  return {
    title: titleFor(result),
    subtitle: result.branch,
    message: `${leadFor(result)} 자세히: pnpm ci:watch --run ${result.runId}`,
  };
}

function devOverlapSectionFor({ devRunId, sharedFailures }: DevOverlap, handoff: string) {
  return [
    "",
    `## dev push 실행 ${devRunId}에서도 실패한 테스트`,
    "",
    ...sharedFailures.map((test) => `- ${testLabelOf(test)}`),
    "",
    `${handoff} dev가 고쳐지면 dev를 병합하고 다시 푸시해 주세요.`,
  ];
}

export function summaryFor(result: WatchResult, healerMessage = "") {
  const lines = [
    `# FRONTEND-CI 실행 ${result.runId}`,
    "",
    `- 결과: ${titleFor(result)}`,
    `- 브랜치: ${result.branch} (${result.sha.slice(0, 8)})`,
    `- 실행: ${result.url}`,
    "",
    leadFor(result),
  ];
  if (result.failures.length > 0) {
    const reportDir = path.join(runDirFor(result.runId), "artifact", "playwright-report");
    lines.push(
      "",
      "## 실패한 테스트",
      "",
      ...result.failures.map((test) => `- ${testLabelOf(test)}`),
      "",
      `CI 실패 기록 열기: npx playwright show-report ${reportDir}`,
    );
  }
  if (result.outcome === "dev-problem") lines.push(...devOverlapSectionFor(result, "healer를 부르지 않았어요."));
  if (result.outcome === "healed") {
    const { healer, devOverlap } = result;
    if (devOverlap) lines.push(...devOverlapSectionFor(devOverlap, "healer에게 넘기지 않았어요."));
    lines.push(
      "",
      "## healer",
      "",
      `- 도구: ${healer.agent}, ${healer.seconds}초`,
      `- 바꾼 파일: ${healer.changedFiles.join(", ") || "없음"}`,
      ...healer.warnings.map((warning) => `- 확인 필요: ${warning}`),
    );
    if (healer.changedFiles.length > 0) {
      lines.push(`- 리포 루트에서 적용: git apply ${path.join(runDirFor(result.runId), "healer.diff")}`);
    }
    lines.push("", "### healer 보고", "", healerMessage.trim() || "(보고 없음)");
  }
  return lines.join("\n") + "\n";
}

export function notify({ title, subtitle, message }: Notice) {
  try {
    execFileSync(
      "osascript",
      [
        "-e",
        "on run argv",
        "-e",
        "display notification (item 3 of argv) with title (item 1 of argv) subtitle (item 2 of argv)",
        "-e",
        "end run",
        title,
        subtitle,
        message,
      ],
      { stdio: "ignore" },
    );
  } catch {
    // macOS가 아니면 알림 없이 로그와 summary.md만 남는다.
  }
}

function orca(args: string[]) {
  return execFileSync("orca", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], timeout: 10_000 });
}

// 카드 문구는 하나뿐이라 덮어쓴다. 바꾸기 전 문구를 돌려주고, Orca를 쓰지 않거나 Orca에 없는 워크트리면 null.
export function markOrcaCard(worktreeDir: string, comment: string) {
  const selector = `path:${worktreeDir}`;
  try {
    const shown = JSON.parse(orca(["worktree", "show", "--worktree", selector, "--json"]));
    const previousComment: string = shown.result.worktree.comment ?? "";
    orca(["worktree", "set", "--worktree", selector, "--comment", comment, "--unread", "--json"]);
    return previousComment;
  } catch {
    return null;
  }
}
