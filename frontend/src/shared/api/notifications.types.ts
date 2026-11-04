import type { FavoriteTarget } from "./favorites.types";

export const WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;

export type Weekday = (typeof WEEKDAYS)[number];

export interface NotificationSchedule {
  /** KST 시각, "HH:MM" */
  time: string;
  /** 하나 이상, 월요일부터 순서대로 */
  weekdays: Weekday[];
}

export interface NotificationSetting extends FavoriteTarget, NotificationSchedule {
  id: string;
}

export interface NotificationSettings {
  settings: NotificationSetting[];
}
