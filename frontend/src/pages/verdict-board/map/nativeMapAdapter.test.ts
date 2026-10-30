import { describe, expect, it } from "@jest/globals";
import { nativeMapErrorReasonFrom } from "./nativeMapAdapter";

describe("native 지도 오류 분류", () => {
  it.each([
    ["지도 설정을 확인하지 못했어요", "auth"],
    ["지도를 불러오지 못했어요", "timeout"],
    ["SDK 인증 실패", undefined],
  ] as const)("앱이 보낸 '%s'는 %s로 분류한다", (message, reason) => {
    expect(nativeMapErrorReasonFrom(message)).toBe(reason);
  });
});
