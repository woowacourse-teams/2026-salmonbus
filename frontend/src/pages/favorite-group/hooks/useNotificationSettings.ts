import { useEffect, useState } from "react";
import { fetchNotificationSettings } from "@/shared/api/notifications.api";
import type { NotificationSetting } from "@/shared/api/notifications.types";

export type NotificationSettingsState =
  { status: "loading" } | { status: "ready"; settings: NotificationSetting[] } | { status: "failed" };

export function useNotificationSettings(enabled: boolean): NotificationSettingsState {
  const [state, setState] = useState<NotificationSettingsState>({ status: "loading" });
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    void fetchNotificationSettings(controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setState(result.ok ? { status: "ready", settings: result.body.settings } : { status: "failed" });
    });
    return () => controller.abort();
  }, [enabled]);
  return state;
}
