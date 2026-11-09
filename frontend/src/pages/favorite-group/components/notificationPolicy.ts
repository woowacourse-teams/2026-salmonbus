import type { ApiFailure } from "@/shared/api/client";
import { WEEKDAYS, type NotificationSchedule, type Weekday } from "@/shared/api/notifications.types";
import { authErrorOf } from "@/shared/utils/authErrorOf";

const WORKDAYS: Weekday[] = ["MON", "TUE", "WED", "THU", "FRI"];
const WEEKEND: Weekday[] = ["SAT", "SUN"];

export const WEEKDAY_LABELS: { [Day in Weekday]: string } = {
  MON: "월",
  TUE: "화",
  WED: "수",
  THU: "목",
  FRI: "금",
  SAT: "토",
  SUN: "일",
};

export const NEW_NOTIFICATION_SCHEDULE: NotificationSchedule = { time: "07:30", weekdays: WORKDAYS };

function sameWeekdays(left: Weekday[], right: Weekday[]): boolean {
  return left.length === right.length && left.every((weekday) => right.includes(weekday));
}

function weekdaysLabelOf(weekdays: Weekday[]): string {
  if (weekdays.length === WEEKDAYS.length) return "매일";
  if (sameWeekdays(weekdays, WORKDAYS)) return "평일";
  if (sameWeekdays(weekdays, WEEKEND)) return "주말";
  return WEEKDAYS.filter((weekday) => weekdays.includes(weekday))
    .map((weekday) => WEEKDAY_LABELS[weekday])
    .join(" ");
}

export function scheduleLabelOf({ time, weekdays }: NotificationSchedule): string {
  return `${weekdaysLabelOf(weekdays)} ${time}`;
}

export function toggledWeekdaysOf(weekdays: Weekday[], toggled: Weekday): Weekday[] {
  if (!weekdays.includes(toggled))
    return WEEKDAYS.filter((weekday) => weekday === toggled || weekdays.includes(weekday));
  return weekdays.length === 1 ? weekdays : weekdays.filter((weekday) => weekday !== toggled);
}

export function notificationErrorOf(failure: ApiFailure): string {
  if (failure.kind === "contract") {
    switch (failure.error.code) {
      case "INVALID_PUSH_DEVICE":
        return "이 기기를 알림 받을 기기로 등록하지 못했어요. 잠시 후 다시 시도해 주세요.";
      case "INVALID_NOTIFICATION_SCHEDULE":
        return "알림 시각과 요일을 다시 확인해 주세요.";
      case "NOTIFICATION_TARGET_NOT_FAVORITE":
        return "즐겨찾기에 저장된 정류장만 알림을 받을 수 있어요. 목록을 다시 확인해 주세요.";
      case "NOTIFICATION_SETTING_NOT_FOUND":
        return "알림 설정을 찾지 못했어요. 목록을 다시 확인해 주세요.";
    }
  }
  if ("status" in failure && failure.status === 404) return "알림 기능을 준비 중이에요.";
  return authErrorOf(failure);
}
