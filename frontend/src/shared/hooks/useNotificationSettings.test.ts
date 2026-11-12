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
  it("저장한 설정과 같은 대상의 설정이 있으면 새 설정으로 바꿔 넣는다", () => {
    const changed = { ...saved, time: "08:10" };
    expect(notificationSettingsWith([saved, oppositeSaved], { saved: changed })).toEqual([oppositeSaved, changed]);
  });

  it("저장한 설정과 같은 대상의 설정이 없으면 새로 넣는다", () => {
    expect(notificationSettingsWith([oppositeSaved], { saved })).toEqual([oppositeSaved, saved]);
    expect(notificationSettingsWith([], { saved })).toEqual([saved]);
  });

  it("지운 설정은 id가 같은 것만 뺀다", () => {
    expect(notificationSettingsWith([saved, oppositeSaved], { removedId: saved.id })).toEqual([oppositeSaved]);
  });

  it("늦게 온 삭제 결과는 그 사이 같은 대상에 새로 저장된 설정을 빼지 않는다", () => {
    const savedAgain = settingOf(target, "20000000-0000-4000-8000-000000000003", "08:10");
    expect(notificationSettingsWith([savedAgain], { removedId: saved.id })).toEqual([savedAgain]);
  });

  it("받은 목록은 고치지 않는다", () => {
    const settings = [saved];
    notificationSettingsWith(settings, { removedId: saved.id });
    expect(settings).toEqual([saved]);
  });
});
