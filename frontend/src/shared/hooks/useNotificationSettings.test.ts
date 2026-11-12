import { describe, expect, it } from "@jest/globals";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import type { NotificationSetting } from "@/shared/api/notifications.types";
import { notificationSettingsWith } from "./useNotificationSettings";

const target: FavoriteTarget = { routeId: "200000001", stopId: "200000011", direction: "UP" };
const oppositeDirection: FavoriteTarget = { ...target, direction: "DOWN" };

function settingOf(target: FavoriteTarget, id: string, time: string): NotificationSetting {
  return { id, ...target, time, weekdays: ["MON", "TUE", "WED", "THU", "FRI"] };
}

const saved = settingOf(target, "20000000-0000-4000-8000-000000000001", "07:40");
const oppositeSaved = settingOf(oppositeDirection, "20000000-0000-4000-8000-000000000002", "18:20");

describe("알림 설정 목록 고치기", () => {
  it("같은 대상의 설정이 있으면 새 설정으로 바꿔 넣는다", () => {
    const changed = { ...saved, time: "08:10" };
    expect(notificationSettingsWith([saved, oppositeSaved], target, changed)).toEqual([oppositeSaved, changed]);
  });

  it("같은 대상의 설정이 없으면 새로 넣는다", () => {
    expect(notificationSettingsWith([oppositeSaved], target, saved)).toEqual([oppositeSaved, saved]);
    expect(notificationSettingsWith([], target, saved)).toEqual([saved]);
  });

  it("새 설정이 없으면 그 대상의 설정을 뺀다", () => {
    expect(notificationSettingsWith([saved, oppositeSaved], target, null)).toEqual([oppositeSaved]);
  });

  it("설정이 없는 대상을 빼도 다른 대상의 설정은 그대로다", () => {
    expect(notificationSettingsWith([oppositeSaved], target, null)).toEqual([oppositeSaved]);
  });

  it("받은 목록은 고치지 않는다", () => {
    const settings = [saved];
    notificationSettingsWith(settings, target, null);
    expect(settings).toEqual([saved]);
  });
});
