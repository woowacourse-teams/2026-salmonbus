import type { ApiResult } from "@/shared/api/client";
import type {
  NotificationSchedule,
  NotificationSetting,
  NotificationSettingChange,
} from "@/shared/api/notifications.types";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import type {
  ApnsEnvironment,
  NotificationPermissionStatus,
  NotificationRegistration,
} from "@/shared/notification/notification.types";
import { NEW_NOTIFICATION_SCHEDULE, notificationErrorOf } from "./notificationPolicy";

export type NotificationRowState =
  | { status: "idle" }
  | { status: "denied" }
  | { status: "editing"; schedule: NotificationSchedule }
  | { status: "asking"; schedule: NotificationSchedule }
  | { status: "saving"; schedule: NotificationSchedule }
  | { status: "failed"; schedule: NotificationSchedule; message: string }
  | { status: "removing" }
  | { status: "removeFailed"; message: string };

export type NotificationRowEvent =
  | { type: "open"; setting: NotificationSetting | null }
  | { type: "cancel" }
  | { type: "submit"; schedule: NotificationSchedule; first: boolean }
  | { type: "answered"; status: NotificationPermissionStatus }
  | { type: "saved"; setting: NotificationSetting }
  | { type: "failed"; message: string }
  | { type: "remove" }
  | { type: "removed"; settingId: string }
  | { type: "removeFailed"; message: string };

export function nextNotificationRowState(
  state: NotificationRowState,
  event: NotificationRowEvent,
): NotificationRowState {
  switch (event.type) {
    case "open":
      return state.status === "idle" || state.status === "denied" || state.status === "removeFailed"
        ? { status: "editing", schedule: event.setting ?? NEW_NOTIFICATION_SCHEDULE }
        : state;
    case "cancel":
      return state.status === "asking" || state.status === "editing" || state.status === "failed"
        ? { status: "idle" }
        : state;
    case "submit":
      if (state.status !== "editing" && state.status !== "failed") return state;
      return { status: event.first ? "asking" : "saving", schedule: event.schedule };
    case "answered":
      if (state.status !== "asking") return state;
      if (event.status === "granted") return { status: "saving", schedule: state.schedule };
      return { status: event.status === "denied" ? "denied" : "idle" };
    case "saved":
      return state.status === "saving" ? { status: "idle" } : state;
    case "failed":
      return state.status === "saving" ? { status: "failed", schedule: state.schedule, message: event.message } : state;
    case "remove":
      return state.status === "idle" || state.status === "removeFailed" ? { status: "removing" } : state;
    case "removed":
      return state.status === "removing" ? { status: "idle" } : state;
    case "removeFailed":
      return state.status === "removing" ? { status: "removeFailed", message: event.message } : state;
  }
}

export function settingChangeOf(event: NotificationRowEvent): NotificationSettingChange | null {
  if (event.type === "saved") return { saved: event.setting };
  if (event.type === "removed") return { removedId: event.settingId };
  return null;
}

export interface NotificationSaveServices {
  requestRegistration: () => Promise<NotificationRegistration | null>;
  registerPushDevice: (token: string, environment: ApnsEnvironment) => Promise<ApiResult<void>>;
  saveNotificationSetting: (
    target: FavoriteTarget,
    schedule: NotificationSchedule,
  ) => Promise<ApiResult<NotificationSetting>>;
}

export async function submitNotification(
  target: FavoriteTarget,
  previous: NotificationSetting | null,
  schedule: NotificationSchedule,
  services: NotificationSaveServices,
  apply: (event: NotificationRowEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  apply({ type: "submit", schedule, first: previous === null });
  if (previous === null) {
    const registration = await services.requestRegistration();
    if (signal?.aborted) return;
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
      ? { type: "removed", settingId: setting.id }
      : { type: "removeFailed", message: notificationErrorOf(result.failure) },
  );
}
