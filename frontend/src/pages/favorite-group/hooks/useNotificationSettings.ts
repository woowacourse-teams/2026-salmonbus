import { useEffect, useState } from "react";
import { fetchNotificationSettings } from "@/shared/api/notifications.api";
import type { NotificationSetting } from "@/shared/api/notifications.types";

export type NotificationSettingsState =
  { status: "loading" } | { status: "ready"; settings: NotificationSetting[] } | { status: "failed" };

/** 화면에 들어올 때 한 번 읽는다. 알림 줄을 보이지 않는 환경이면 읽지 않는다. */
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
