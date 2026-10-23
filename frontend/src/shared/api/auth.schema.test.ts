import { describe, expect, it } from "@jest/globals";
import contract from "../../../../contract/auth-v1.openapi.json";
import { isAuthSession, isMember } from "./auth.schema";

describe("인증 응답 검증", () => {
  const examples = contract.paths["/api/auth/session"].get.responses["200"].content["application/json"].examples;
  it.each(Object.entries(examples))("공유 계약의 %s 세션 응답을 받는다", (_name, example) => {
    expect(isAuthSession(example.value)).toBe(true);
  });
  it.each(contract.components.schemas.MemberResponse.properties.provider.enum)(
    "%s 회원의 내 정보 응답을 받는다",
    (provider) => {
      const profile = contract.paths["/api/me"].get.responses["200"].content["application/json"].examples.profile.value;
      expect(isMember({ ...profile, provider })).toBe(true);
    },
  );
  it("깨진 인증 응답을 회원으로 받아들이지 않는다", () => {
    expect(isAuthSession({ authenticated: true, refreshable: false })).toBe(false);
    expect(isAuthSession({ authenticated: false, refreshable: true })).toBe(true);
    expect(isAuthSession({ authenticated: false, refreshable: false, member: {} })).toBe(false);
  });
});
