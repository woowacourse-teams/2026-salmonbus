import { useEffect, useState } from "react";
import { fetchNotificationSettings } from "@/shared/api/notifications.api";
import type { NotificationSetting, NotificationSettingChange } from "@/shared/api/notifications.types";
import { sameFavorite } from "@/shared/utils/favoritePolicy";

type SettingsLoad = { status: "loading" | "failed" } | { status: "ready"; settings: NotificationSetting[] };

export type NotificationSettingsState =
  | { status: "unsupported" | "loading" | "failed" }
  | {
      status: "ready";
      settings: NotificationSetting[];
      applySettingChange: (change: NotificationSettingChange) => void;
    };

export function notificationSettingsWith(
  settings: NotificationSetting[],
  change: NotificationSettingChange,
): NotificationSetting[] {
  if ("removedId" in change) return settings.filter((candidate) => candidate.id !== change.removedId);
  return [...settings.filter((candidate) => !sameFavorite(candidate, change.saved)), change.saved];
}

export function useNotificationSettings(enabled: boolean): NotificationSettingsState {
  const [load, setLoad] = useState<SettingsLoad>({ status: "loading" });
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    void fetchNotificationSettings(controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setLoad(result.ok ? { status: "ready", settings: result.body.settings } : { status: "failed" });
    });
    return () => controller.abort();
  }, [enabled]);
  if (!enabled) return { status: "unsupported" };
  if (load.status !== "ready") return load;
  return {
    ...load,
    applySettingChange: (change) =>
      setLoad((current) =>
        current.status === "ready"
          ? { status: "ready", settings: notificationSettingsWith(current.settings, change) }
          : current,
      ),
  };
}
