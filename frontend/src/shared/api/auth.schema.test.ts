import { describe, expect, it } from "@jest/globals";
import { isAuthSession } from "./auth.schema";

describe("인증 응답 검증", () => {
  it("깨진 인증 응답을 회원으로 받아들이지 않는다", () => {
    expect(isAuthSession({ authenticated: true, refreshable: false })).toBe(false);
    expect(isAuthSession({ authenticated: false, refreshable: true })).toBe(true);
    expect(isAuthSession({ authenticated: false, refreshable: false, member: {} })).toBe(false);
  });
});
