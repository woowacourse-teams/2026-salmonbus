import { parsedMemberRequest } from "./auth.api";
import { emptyFrom } from "./favorites.schema";
import type { FavoriteTarget } from "./favorites.types";
import { notificationSettingFrom, notificationSettingsFrom } from "./notifications.schema";
import type { ApnsEnvironment } from "../notification/notification.types";
import type { NotificationSchedule } from "./notifications.types";

const SETTINGS = "/api/me/notification-settings";

// BE 리뷰에서 device token이 경로 대신 본문으로 옮겨질 수 있어 주소는 이 파일에서만 만든다.
const pushDevicePathOf = (token: string) => `/api/me/push-devices/${encodeURIComponent(token)}`;

export const registerPushDevice = (token: string, environment: ApnsEnvironment) =>
  parsedMemberRequest(pushDevicePathOf(token), { method: "PUT", body: { environment } }, emptyFrom);
export const unregisterPushDevice = (token: string) =>
  parsedMemberRequest(pushDevicePathOf(token), { method: "DELETE" }, emptyFrom);
export const fetchNotificationSettings = (signal?: AbortSignal) =>
  parsedMemberRequest(SETTINGS, signal ? { signal } : {}, notificationSettingsFrom);
export const saveNotificationSetting = (
  { routeId, stopId, direction }: FavoriteTarget,
  schedule: NotificationSchedule,
) =>
  parsedMemberRequest(
    SETTINGS,
    { method: "PUT", body: { routeId, stopId, direction, time: schedule.time, weekdays: schedule.weekdays } },
    notificationSettingFrom,
  );
export const deleteNotificationSetting = (id: string) =>
  parsedMemberRequest(`${SETTINGS}/${encodeURIComponent(id)}`, { method: "DELETE" }, emptyFrom);
