import { describe, expect, it } from "@jest/globals";
import { runEndingFor } from "./ci.ts";

const timeoutMessage = "The job has exceeded the maximum execution time of 30m0s";
const supersededMessage =
  "Canceling since a higher priority waiting request for FRONTEND-CI-refs/pull/175/merge exists";

describe("runEndingFor", () => {
  it("성공한 실행은 조용히 끝낸다", () => {
    expect(runEndingFor("success", [])).toEqual({ kind: "quiet" });
  });

  it("새 푸시에 밀려 취소된 실행은 조용히 끝낸다", () => {
    expect(runEndingFor("cancelled", [supersededMessage, "The operation was canceled."])).toEqual({ kind: "quiet" });
  });

  it("실패한 실행은 실패 기록을 읽으러 간다", () => {
    expect(runEndingFor("failure", [])).toEqual({ kind: "failure" });
  });

  it("job 시간 제한에 걸려 취소된 실행은 시간 초과로 알린다", () => {
    expect(runEndingFor("cancelled", [timeoutMessage, "The operation was canceled."])).toEqual({
      kind: "stopped",
      title: "CI 시간 초과",
    });
  });

  it("timed_out으로 끝난 실행도 시간 초과로 알린다", () => {
    expect(runEndingFor("timed_out", [])).toEqual({ kind: "stopped", title: "CI 시간 초과" });
  });

  it("이유를 모르는 취소와 그 밖의 결론은 결론 그대로 알린다", () => {
    expect(runEndingFor("cancelled", ["The operation was canceled."])).toEqual({
      kind: "stopped",
      title: "CI cancelled",
    });
    expect(runEndingFor("startup_failure", [])).toEqual({ kind: "stopped", title: "CI startup_failure" });
  });
});
