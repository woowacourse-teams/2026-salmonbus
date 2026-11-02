import { useState } from "react";
import { WEEKDAYS, type NotificationSchedule } from "@/shared/api/notifications.types";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { toggledWeekdaysOf, WEEKDAY_LABELS } from "@/shared/utils/notificationPolicy";
import * as formStyles from "@/shared/styles/form.css";
import * as groupStyles from "./GroupDetails.css";
import * as styles from "./NotificationRow.css";

interface NotificationSheetProps {
  stopLabel: string;
  initialSchedule: NotificationSchedule;
  busy: boolean;
  /** 저장 요청이 서버로 간 뒤에는 되돌릴 수 없어 닫지 않는다. 권한을 묻는 동안에는 닫을 수 있다. */
  cancellable: boolean;
  error: string | null;
  onCancel: () => void;
  onSave: (schedule: NotificationSchedule) => void;
}

export function NotificationSheet({
  stopLabel,
  initialSchedule,
  busy,
  cancellable,
  error,
  onCancel,
  onSave,
}: NotificationSheetProps) {
  const [time, setTime] = useState(initialSchedule.time);
  const [weekdays, setWeekdays] = useState(initialSchedule.weekdays);
  return (
    <ConfirmDialog
      className={styles.sheet}
      titleClassName={groupStyles.dialogTitle}
      title="출발 알림"
      onCancel={() => {
        if (cancellable) onCancel();
      }}
    >
      <p className={groupStyles.description}>{stopLabel}</p>
      <label className={styles.field}>
        시각
        <input
          className={styles.time}
          type="time"
          step={60}
          required
          value={time}
          disabled={busy}
          onChange={(event) => setTime(event.target.value)}
        />
      </label>
      <div className={styles.weekdays} role="group" aria-label="요일">
        {WEEKDAYS.map((weekday) => (
          <button
            key={weekday}
            type="button"
            className={styles.weekday}
            aria-label={`${WEEKDAY_LABELS[weekday]}요일`}
            aria-pressed={weekdays.includes(weekday)}
            disabled={busy}
            onClick={() => setWeekdays((current) => toggledWeekdaysOf(current, weekday))}
          >
            {WEEKDAY_LABELS[weekday]}
          </button>
        ))}
      </div>
      {error && (
        <p className={formStyles.error} role="alert">
          {error}
        </p>
      )}
      <div className={groupStyles.dialogActions}>
        <button className={groupStyles.cancel} disabled={!cancellable} onClick={onCancel}>
          취소
        </button>
        <button
          className={groupStyles.confirm}
          disabled={busy || time === ""}
          onClick={() => onSave({ time, weekdays })}
        >
          {busy ? "저장 중…" : "저장"}
        </button>
      </div>
    </ConfirmDialog>
  );
}
