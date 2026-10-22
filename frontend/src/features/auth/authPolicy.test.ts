import { describe, expect, it } from "@jest/globals";
import { boardReturnTo, isAuthSession, localReturnTo, nicknameErrorOf } from "./authPolicy";

describe("로그인 입력과 복귀 경로", () => {
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
  it.each(["가", "12345678901", "가 나", "가나!", "가나😀", "ㄱㄴ"])("잘못된 닉네임 %s를 거절한다", (nickname) => {
    expect(nicknameErrorOf(nickname)).not.toBeNull();
  });
  it("앞뒤 공백만 제거하고 한글·영문·숫자를 허용한다", () => {
    expect(nicknameErrorOf("  살몽A1  ")).toBeNull();
  });
  it("깨진 인증 응답을 회원으로 받아들이지 않는다", () => {
    expect(isAuthSession({ authenticated: true, refreshable: false })).toBe(false);
    expect(isAuthSession({ authenticated: false, refreshable: true })).toBe(true);
    expect(isAuthSession({ authenticated: false, refreshable: false, member: {} })).toBe(false);
  });
});
