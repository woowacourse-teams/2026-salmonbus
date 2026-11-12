import type { FavoriteTarget } from "./favorites.types";

export const WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;

export type Weekday = (typeof WEEKDAYS)[number];

export interface NotificationSchedule {
  time: string;
  weekdays: Weekday[];
}

export interface NotificationSetting extends FavoriteTarget, NotificationSchedule {
  id: string;
}

export type NotificationSettingChange = { saved: NotificationSetting } | { removedId: string };

export interface NotificationSettings {
  settings: NotificationSetting[];
}
