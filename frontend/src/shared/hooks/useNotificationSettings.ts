import { useEffect, useState } from "react";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { fetchNotificationSettings } from "@/shared/api/notifications.api";
import type { NotificationSetting } from "@/shared/api/notifications.types";
import { sameFavorite } from "@/shared/utils/favoritePolicy";

type SettingsLoad = { status: "loading" | "failed" } | { status: "ready"; settings: NotificationSetting[] };

export type NotificationSettingsState =
  | { status: "unsupported" | "loading" | "failed" }
  | {
      status: "ready";
      settings: NotificationSetting[];
      replaceSetting: (target: FavoriteTarget, setting: NotificationSetting | null) => void;
    };

export function notificationSettingsWith(
  settings: NotificationSetting[],
  target: FavoriteTarget,
  setting: NotificationSetting | null,
): NotificationSetting[] {
  const others = settings.filter((candidate) => !sameFavorite(candidate, target));
  return setting === null ? others : [...others, setting];
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
    replaceSetting: (target, setting) =>
      setLoad((current) =>
        current.status === "ready"
          ? { status: "ready", settings: notificationSettingsWith(current.settings, target, setting) }
          : current,
      ),
  };
}
