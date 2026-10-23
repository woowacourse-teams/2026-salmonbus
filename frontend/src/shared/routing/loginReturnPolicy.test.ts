import { describe, expect, it } from "@jest/globals";
import { boardReturnTo, localReturnTo } from "./loginReturnPolicy";

describe("로그인 복귀 경로", () => {
  it.each([
    "//evil.test",
    "https://evil.test",
    "/routes/123456789?direction=UP&direction=DOWN",
    "/routes/123456789?x=1",
    "/routes/123456789?stopId=A?x=1",
    "/login",
    "/mypage",
  ])("서버 복귀 경로 %s를 홈으로 제한한다", (path) => {
    expect(boardReturnTo(path)).toBe("/");
  });
  it("허용한 방면·정류장을 보존하고 회원 화면은 클라이언트에서만 복원한다", () => {
    const path = "/routes/123456789?direction=DOWN&stopId=stop_1";
    expect(boardReturnTo(path)).toBe(path);
    expect(localReturnTo("/mypage")).toBe("/mypage");
    expect(localReturnTo("/?q=3330")).toBe("/?q=3330");
  });
});
