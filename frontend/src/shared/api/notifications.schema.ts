import { isRecord } from "@/shared/utils/valueGuards";
import { directionFrom, identifierFrom, listFrom, uuidFrom } from "./favorites.schema";
import { WEEKDAYS, type NotificationSetting, type NotificationSettings, type Weekday } from "./notifications.types";

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

function timeFrom(value: unknown): string | null {
  return typeof value === "string" && TIME.test(value) ? value : null;
}

function weekdaysFrom(value: unknown): Weekday[] | null {
  if (!Array.isArray(value) || value.length === 0) return null;
  const weekdays = WEEKDAYS.filter((weekday) => value.includes(weekday));
  return weekdays.length === value.length ? weekdays : null;
}

export function notificationSettingFrom(value: unknown): NotificationSetting | null {
  if (!isRecord(value)) return null;
  const id = uuidFrom(value.id);
  const routeId = identifierFrom(value.routeId);
  const stopId = identifierFrom(value.stopId);
  const direction = directionFrom(value.direction);
  const time = timeFrom(value.time);
  const weekdays = weekdaysFrom(value.weekdays);
  return id === null || routeId === null || stopId === null || direction === null || time === null || weekdays === null
    ? null
    : { id, routeId, stopId, direction, time, weekdays };
}

export function notificationSettingsFrom(value: unknown): NotificationSettings | null {
  if (!isRecord(value)) return null;
  const settings = listFrom(value.settings, Number.POSITIVE_INFINITY, notificationSettingFrom);
  return settings === null ? null : { settings };
}
