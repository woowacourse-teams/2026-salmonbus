import { describe, expect, it } from "@jest/globals";
import { withdrawalNoticeLabelOf } from "./withdrawalNoticeLabelOf";

describe("회원탈퇴 결과 안내 문구", () => {
  it.each([
    ["withdrawn", "회원탈퇴가 완료됐어요"],
    ["cancelled", "회원탈퇴를 취소했어요"],
    ["failed", "회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요."],
  ] as const)("%s 결과는 '%s'로 안내한다", (result, label) => {
    expect(withdrawalNoticeLabelOf(result)).toBe(label);
  });
});
