import { describe, expect, it } from "@jest/globals";
import { loginResultPathFor } from "./loginResultPathFor";

describe("로그인 결과를 안내할 주소", () => {
  it.each(["failed", "cancelled"] as const)("%s 결과와 처음 가려던 곳을 로그인 화면 주소에 담는다", (result) => {
    expect(loginResultPathFor(result, "/mypage")).toBe(`/login?returnTo=%2Fmypage&login=${result}`);
  });

  it("복귀 경로에 쿼리가 있어도 한 값으로 담는다", () => {
    expect(loginResultPathFor("cancelled", "/routes/200000001?direction=UP&stopId=11")).toBe(
      "/login?returnTo=%2Froutes%2F200000001%3Fdirection%3DUP%26stopId%3D11&login=cancelled",
    );
  });

  it("처음 가려던 곳을 모르면 홈으로 돌아가게 한다", () => {
    expect(loginResultPathFor("failed", undefined)).toBe("/login?returnTo=%2F&login=failed");
  });
});
