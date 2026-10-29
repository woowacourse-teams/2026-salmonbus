import { describe, expect, it, jest } from "@jest/globals";
import { trackAsMember } from "./track";

const mockCalls: string[] = [];

jest.mock("./analytics", () => ({
  isAnalyticsEnabled: () => true,
  setAnalyticsMemberId: (memberId: string | null) => mockCalls.push(`identify ${memberId}`),
}));

jest.mock("@amplitude/unified", () => ({
  track: (event: string) => mockCalls.push(`track ${event}`),
}));

describe("회원으로 이벤트 보내기", () => {
  it("회원 ID를 먼저 맞춘 뒤 이벤트를 보낸다", () => {
    trackAsMember("member-1", "login_finished", { provider: "KAKAO", result: "succeeded" });
    expect(mockCalls).toEqual(["identify member-1", "track login_finished"]);
  });
});
