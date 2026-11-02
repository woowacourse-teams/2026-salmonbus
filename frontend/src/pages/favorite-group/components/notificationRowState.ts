import type { ApiResult } from "@/shared/api/client";
import type { NotificationSchedule, NotificationSetting } from "@/shared/api/notifications.types";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import type {
  ApnsEnvironment,
  NotificationPermissionStatus,
  NotificationRegistration,
} from "@/shared/notification/notification.types";
import { NEW_NOTIFICATION_SCHEDULE, notificationErrorOf } from "@/shared/utils/notificationPolicy";

/** 정류장 하나의 알림 줄. editing, asking, saving, failed 동안 시각과 요일 창이 열려 있다. */
export type NotificationRowState =
  | { status: "off" }
  | { status: "denied" }
  | { status: "on"; setting: NotificationSetting }
  | { status: "editing"; schedule: NotificationSchedule; previous: NotificationSetting | null }
  | { status: "asking"; schedule: NotificationSchedule }
  | { status: "saving"; schedule: NotificationSchedule; previous: NotificationSetting | null }
  | { status: "failed"; schedule: NotificationSchedule; previous: NotificationSetting | null; message: string }
  | { status: "removing"; setting: NotificationSetting }
  | { status: "removeFailed"; setting: NotificationSetting; message: string };

export type NotificationRowEvent =
  | { type: "open" }
  | { type: "cancel" }
  | { type: "submit"; schedule: NotificationSchedule }
  | { type: "answered"; status: NotificationPermissionStatus }
  | { type: "saved"; setting: NotificationSetting }
  | { type: "failed"; message: string }
  | { type: "remove" }
  | { type: "removed" }
  | { type: "removeFailed"; message: string };

export function notificationRowStateOf(setting: NotificationSetting | null): NotificationRowState {
  return setting === null ? { status: "off" } : { status: "on", setting };
}

/** 스위치와 요약 줄이 보여 줄 설정. 저장이 끝나기 전에는 이전 값을 보인다. */
export function shownSettingOf(state: NotificationRowState): NotificationSetting | null {
  switch (state.status) {
    case "on":
    case "removing":
    case "removeFailed":
      return state.setting;
    case "editing":
    case "saving":
    case "failed":
      return state.previous;
    case "off":
    case "denied":
    case "asking":
      return null;
  }
}

/** 지금 상태에서 받을 수 없는 사건이면 상태를 그대로 돌려준다. */
export function nextNotificationRowState(
  state: NotificationRowState,
  event: NotificationRowEvent,
): NotificationRowState {
  switch (event.type) {
    case "open":
      if (state.status === "off" || state.status === "denied")
        return { status: "editing", schedule: NEW_NOTIFICATION_SCHEDULE, previous: null };
      if (state.status === "on" || state.status === "removeFailed")
        return { status: "editing", schedule: state.setting, previous: state.setting };
      return state;
    case "cancel":
      return state.status === "editing" || state.status === "failed" ? notificationRowStateOf(state.previous) : state;
    case "submit":
      if (state.status !== "editing" && state.status !== "failed") return state;
      return state.previous === null
        ? { status: "asking", schedule: event.schedule }
        : { status: "saving", schedule: event.schedule, previous: state.previous };
    case "answered":
      if (state.status !== "asking") return state;
      if (event.status === "granted") return { status: "saving", schedule: state.schedule, previous: null };
      return event.status === "denied" ? { status: "denied" } : { status: "off" };
    case "saved":
      return state.status === "saving" ? { status: "on", setting: event.setting } : state;
    case "failed":
      return state.status === "saving"
        ? { status: "failed", schedule: state.schedule, previous: state.previous, message: event.message }
        : state;
    case "remove":
      return state.status === "on" || state.status === "removeFailed"
        ? { status: "removing", setting: state.setting }
        : state;
    case "removed":
      return state.status === "removing" ? { status: "off" } : state;
    case "removeFailed":
      return state.status === "removing"
        ? { status: "removeFailed", setting: state.setting, message: event.message }
        : state;
  }
}

export interface NotificationSaveServices {
  /** 앱이 답하지 않거나 앱 밖이면 null이다. 권한을 고르지 않은 것과 같게 본다. */
  requestRegistration: () => Promise<NotificationRegistration | null>;
  registerPushDevice: (token: string, environment: ApnsEnvironment) => Promise<ApiResult<void>>;
  saveNotificationSetting: (
    target: FavoriteTarget,
    schedule: NotificationSchedule,
  ) => Promise<ApiResult<NotificationSetting>>;
}

/**
 * 창의 저장 버튼. 새 알림이면 권한을 묻고, 허락받은 기기를 등록한 뒤 설정을 저장한다.
 * 이미 켜진 알림은 시각과 요일만 저장한다. 단계마다 apply로 사건을 넘긴다.
 */
export async function submitNotification(
  target: FavoriteTarget,
  previous: NotificationSetting | null,
  schedule: NotificationSchedule,
  services: NotificationSaveServices,
  apply: (event: NotificationRowEvent) => void,
): Promise<void> {
  apply({ type: "submit", schedule });
  if (previous === null) {
    const registration = await services.requestRegistration();
    apply({ type: "answered", status: registration?.status ?? "undetermined" });
    if (registration?.status !== "granted") return;
    if (registration.token !== null) {
      const device = await services.registerPushDevice(registration.token, registration.environment);
      if (!device.ok) {
        apply({ type: "failed", message: notificationErrorOf(device.failure) });
        return;
      }
    }
  }
  const result = await services.saveNotificationSetting(target, schedule);
  apply(
    result.ok
      ? { type: "saved", setting: result.body }
      : { type: "failed", message: notificationErrorOf(result.failure) },
  );
}

/** 켜진 스위치를 끄면 바로 지운다. 서버에 이미 없는 설정은 꺼진 것으로 본다. */
export async function removeNotification(
  setting: NotificationSetting,
  deleteNotificationSetting: (id: string) => Promise<ApiResult<void>>,
  apply: (event: NotificationRowEvent) => void,
): Promise<void> {
  apply({ type: "remove" });
  const result = await deleteNotificationSetting(setting.id);
  const alreadyRemoved =
    !result.ok && result.failure.kind === "contract" && result.failure.error.code === "NOTIFICATION_SETTING_NOT_FOUND";
  apply(
    result.ok || alreadyRemoved
      ? { type: "removed" }
      : { type: "removeFailed", message: notificationErrorOf(result.failure) },
  );
}
