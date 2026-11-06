import { parsedMemberRequest } from "./auth.api";
import { emptyFrom } from "./favorites.schema";
import type { FavoriteTarget } from "./favorites.types";
import { notificationSettingFrom, notificationSettingsFrom } from "./notifications.schema";
import type { ApnsEnvironment } from "../notification/notification.types";
import type { NotificationSchedule } from "./notifications.types";

const SETTINGS = "/api/me/notification-settings";
const PUSH_DEVICES = "/api/me/push-devices";

export const registerPushDevice = (token: string, environment: ApnsEnvironment) =>
  parsedMemberRequest(PUSH_DEVICES, { method: "PUT", body: { token, environment } }, emptyFrom);
export const unregisterPushDevice = (token: string) =>
  parsedMemberRequest(`${PUSH_DEVICES}/unregister`, { method: "POST", body: { token } }, emptyFrom);
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
