import { useRef, useState } from "react";
import { track } from "@/shared/analytics/track";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { deleteNotificationSetting, saveNotificationSetting } from "@/shared/api/notifications.api";
import type {
  NotificationSchedule,
  NotificationSetting,
  NotificationSettingChange,
} from "@/shared/api/notifications.types";
import { isSupportedInApp, sendToApp } from "@/shared/bridge/bridge";
import { Switch } from "@/shared/components/Switch";
import { useResultHaptic } from "@/shared/haptic/haptic";
import { requestNotificationPermission } from "@/shared/notification/nativeNotification";
import { registerThisDevice } from "@/shared/notification/useNotificationRegistration";
import { scheduleLabelOf } from "./notificationPolicy";
import * as formStyles from "@/shared/styles/form.css";
import { NotificationSheet } from "./NotificationSheet";
import {
  nextNotificationRowState,
  removeNotification,
  settingChangeOf,
  submitNotification,
  type NotificationRowEvent,
  type NotificationRowState,
} from "./notificationRowState";
import * as styles from "./NotificationRow.css";

interface NotificationRowProps {
  target: FavoriteTarget;
  stopLabel: string;
  setting: NotificationSetting | null;
  onSettingChange: (change: NotificationSettingChange) => void;
}

export function NotificationRow({ target, stopLabel, setting, onSettingChange }: NotificationRowProps) {
  const [state, setState] = useState<NotificationRowState>({ status: "idle" });
  const playResultHaptic = useResultHaptic();
  const permissionAsk = useRef<AbortController | null>(null);

  function apply(event: NotificationRowEvent) {
    setState((current) => nextNotificationRowState(current, event));
    if (event.type === "saved" || event.type === "failed") playResultHaptic(event.type === "saved");
    const change = settingChangeOf(event);
    if (change !== null) onSettingChange(change);
  }

  function save(schedule: NotificationSchedule) {
    if (state.status !== "editing" && state.status !== "failed") return;
    const isFirst = setting === null;
    const ask = new AbortController();
    permissionAsk.current = ask;
    void submitNotification(
      target,
      setting,
      schedule,
      {
        requestRegistration: requestNotificationPermission,
        registerPushDevice: registerThisDevice,
        saveNotificationSetting,
      },
      (event) => {
        apply(event);
        if (event.type === "saved")
          track("notification_settings_saved", {
            route_id: event.setting.routeId,
            direction: event.setting.direction,
            stop_id: event.setting.stopId,
            time: event.setting.time,
            weekday_count: event.setting.weekdays.length,
            is_first: isFirst,
          });
      },
      ask.signal,
    );
  }

  const sheetOpen =
    state.status === "editing" || state.status === "asking" || state.status === "saving" || state.status === "failed";
  const busy = state.status === "asking" || state.status === "saving" || state.status === "removing";
  return (
    <div className={styles.row}>
      <div className={styles.line}>
        {setting ? (
          <button
            type="button"
            className={styles.summary}
            aria-haspopup="dialog"
            disabled={busy}
            onClick={(event) => {
              event.currentTarget.focus();
              apply({ type: "open", setting });
            }}
          >
            {scheduleLabelOf(setting)}
          </button>
        ) : (
          <span className={styles.offLabel}>출발 알림</span>
        )}
        <Switch
          checked={setting !== null}
          disabled={busy}
          aria-label={`${stopLabel} 출발 알림`}
          onChange={(event) => {
            event.currentTarget.focus();
            if (event.target.checked) apply({ type: "open", setting });
            else if (setting !== null && (state.status === "idle" || state.status === "removeFailed"))
              void removeNotification(setting, deleteNotificationSetting, apply);
          }}
        />
      </div>
      {state.status === "denied" && (
        <div className={styles.notice}>
          <p className={styles.noticeText} role="status">
            iPhone 설정에서 연어버스의 알림을 허용해 주세요.
          </p>
          {isSupportedInApp("settings.open") && (
            <button type="button" className={styles.settingsButton} onClick={() => sendToApp("settings.open", {})}>
              설정 열기
            </button>
          )}
        </div>
      )}
      {state.status === "removeFailed" && (
        <p className={formStyles.error} role="alert">
          {state.message}
        </p>
      )}
      {sheetOpen && (
        <NotificationSheet
          stopLabel={stopLabel}
          initialSchedule={state.schedule}
          busy={busy}
          cancellable={state.status !== "saving"}
          error={state.status === "failed" ? state.message : null}
          onCancel={() => {
            permissionAsk.current?.abort();
            apply({ type: "cancel" });
          }}
          onSave={save}
        />
      )}
    </div>
  );
}

export function NotificationRowPlaceholder() {
  return (
    <div className={styles.row} aria-hidden="true">
      <div className={styles.line} />
    </div>
  );
}
