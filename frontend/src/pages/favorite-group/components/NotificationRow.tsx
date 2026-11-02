import { useState } from "react";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { deleteNotificationSetting, registerPushDevice, saveNotificationSetting } from "@/shared/api/notifications.api";
import type { NotificationSchedule, NotificationSetting } from "@/shared/api/notifications.types";
import { isSupportedInApp, sendToApp } from "@/shared/bridge/bridge";
import { Switch } from "@/shared/components/Switch";
import { useResultHaptic } from "@/shared/haptic/haptic";
import { scheduleLabelOf } from "@/shared/utils/notificationPolicy";
import * as formStyles from "@/shared/styles/form.css";
import { requestNotificationRegistration } from "../notificationRegistration";
import { NotificationSheet } from "./NotificationSheet";
import {
  nextNotificationRowState,
  notificationRowStateOf,
  removeNotification,
  shownSettingOf,
  submitNotification,
  type NotificationRowEvent,
} from "./notificationRowState";
import * as styles from "./NotificationRow.css";

interface NotificationRowProps {
  target: FavoriteTarget;
  stopLabel: string;
  initialSetting: NotificationSetting | null;
}

export function NotificationRow({ target, stopLabel, initialSetting }: NotificationRowProps) {
  const [state, setState] = useState(() => notificationRowStateOf(initialSetting));
  const playResultHaptic = useResultHaptic();

  function apply(event: NotificationRowEvent) {
    setState((current) => nextNotificationRowState(current, event));
    if (event.type === "saved" || event.type === "failed") playResultHaptic(event.type === "saved");
  }

  function save(schedule: NotificationSchedule) {
    if (state.status !== "editing" && state.status !== "failed") return;
    void submitNotification(
      target,
      state.previous,
      schedule,
      { requestRegistration: requestNotificationRegistration, registerPushDevice, saveNotificationSetting },
      apply,
    );
  }

  const shownSetting = shownSettingOf(state);
  const sheetOpen =
    state.status === "editing" || state.status === "asking" || state.status === "saving" || state.status === "failed";
  const busy = state.status === "asking" || state.status === "saving" || state.status === "removing";
  return (
    <div className={styles.row}>
      <div className={styles.line}>
        {shownSetting ? (
          <button
            type="button"
            className={styles.summary}
            aria-haspopup="dialog"
            disabled={busy}
            onClick={(event) => {
              event.currentTarget.focus();
              apply({ type: "open" });
            }}
          >
            {scheduleLabelOf(shownSetting)}
          </button>
        ) : (
          <span className={styles.offLabel}>출발 알림</span>
        )}
        <Switch
          checked={shownSetting !== null}
          disabled={busy}
          aria-label={`${stopLabel} 출발 알림`}
          onChange={(event) => {
            event.currentTarget.focus();
            if (event.target.checked) apply({ type: "open" });
            else if (state.status === "on" || state.status === "removeFailed")
              void removeNotification(state.setting, deleteNotificationSetting, apply);
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
          error={state.status === "failed" ? state.message : null}
          onCancel={() => apply({ type: "cancel" })}
          onSave={save}
        />
      )}
    </div>
  );
}
