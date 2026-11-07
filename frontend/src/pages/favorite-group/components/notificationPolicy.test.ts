import { describe, expect, it } from "@jest/globals";
import type { ApiFailure } from "@/shared/api/client";
import type { ErrorCode } from "@/shared/api/apiError";
import type { Weekday } from "@/shared/api/notifications.types";
import {
  NEW_NOTIFICATION_SCHEDULE,
  notificationErrorOf,
  scheduleLabelOf,
  toggledWeekdaysOf,
} from "./notificationPolicy";

function contractFailure(code: ErrorCode, status: number): ApiFailure {
  return { kind: "contract", error: { code, message: code, requestId: "request-1" }, status, retryAfterMs: null };
}

describe("알림 정책", () => {
  it("새 알림은 평일 07:30으로 시작한다", () => {
    expect(NEW_NOTIFICATION_SCHEDULE).toEqual({ time: "07:30", weekdays: ["MON", "TUE", "WED", "THU", "FRI"] });
    expect(scheduleLabelOf(NEW_NOTIFICATION_SCHEDULE)).toBe("평일 07:30");
  });

  it.each<[Weekday[], string]>([
    [["MON", "TUE", "WED", "THU", "FRI"], "평일 07:40"],
    [["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"], "매일 07:40"],
    [["SAT", "SUN"], "주말 07:40"],
    [["MON", "WED", "FRI"], "월 수 금 07:40"],
    [["SUN", "MON"], "월 일 07:40"],
    [["MON", "TUE", "WED", "THU", "FRI", "SAT"], "월 화 수 목 금 토 07:40"],
    [["SAT"], "토 07:40"],
  ])("요일 %j은 %s로 요약한다", (weekdays, label) => {
    expect(scheduleLabelOf({ time: "07:40", weekdays })).toBe(label);
  });

  it("요일을 켜면 월요일부터 순서로 넣고, 끄면 뺀다", () => {
    expect(toggledWeekdaysOf(["FRI"], "MON")).toEqual(["MON", "FRI"]);
    expect(toggledWeekdaysOf(["MON", "WED"], "SUN")).toEqual(["MON", "WED", "SUN"]);
    expect(toggledWeekdaysOf(["MON", "WED"], "MON")).toEqual(["WED"]);
  });

  it("마지막 남은 요일은 끌 수 없다", () => {
    expect(toggledWeekdaysOf(["WED"], "WED")).toEqual(["WED"]);
  });

  it.each<[ErrorCode, number, string]>([
    ["INVALID_PUSH_DEVICE", 400, "이 기기를 알림 받을 기기로 등록하지 못했어요. 잠시 후 다시 시도해 주세요."],
    ["INVALID_NOTIFICATION_SCHEDULE", 400, "알림 시각과 요일을 다시 확인해 주세요."],
    [
      "NOTIFICATION_TARGET_NOT_FAVORITE",
      422,
      "즐겨찾기에 저장된 정류장만 알림을 받을 수 있어요. 목록을 다시 확인해 주세요.",
    ],
    ["NOTIFICATION_SETTING_NOT_FOUND", 404, "알림 설정을 찾지 못했어요. 목록을 다시 확인해 주세요."],
  ])("%s 오류를 알림 문구로 바꾼다", (code, status, message) => {
    expect(notificationErrorOf(contractFailure(code, status))).toBe(message);
  });

  it("알림 경로가 아직 없는 서버의 404는 준비 중으로 안내한다", () => {
    expect(notificationErrorOf(contractFailure("ENDPOINT_NOT_FOUND", 404))).toBe("알림 기능을 준비 중이에요.");
    expect(notificationErrorOf({ kind: "malformed", status: 404, requestId: null })).toBe("알림 기능을 준비 중이에요.");
  });

  it("알림과 상관없는 오류는 회원 요청 문구를 쓴다", () => {
    expect(notificationErrorOf(contractFailure("UNAUTHENTICATED", 401))).toBe(
      "로그인이 만료됐어요. 다시 로그인해 주세요.",
    );
    expect(notificationErrorOf({ kind: "timeout" })).toBe("요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.");
  });
});
