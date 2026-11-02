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

// SAL-172가 bridge 타입을 커밋하면 그쪽 ApnsEnvironment를 쓰고 이 유니온은 지운다.
export type ApnsEnvironment = "SANDBOX" | "PRODUCTION";
