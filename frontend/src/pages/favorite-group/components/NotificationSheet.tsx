import { useId, useState } from "react";
import { WEEKDAYS, type NotificationSchedule } from "@/shared/api/notifications.types";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { toggledWeekdaysOf, WEEKDAY_LABELS } from "./notificationPolicy";
import * as formStyles from "@/shared/styles/form.css";
import * as groupStyles from "./GroupDetails.css";
import * as styles from "./NotificationRow.css";

const HOURS = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, "0"));
const MINUTES = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, "0"));

interface NotificationSheetProps {
  stopLabel: string;
  initialSchedule: NotificationSchedule;
  busy: boolean;
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
  const timeLabelId = useId();
  const [hour, minute] = time.split(":");
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
      <div className={styles.field} role="group" aria-labelledby={timeLabelId}>
        <span id={timeLabelId}>시각</span>
        <span className={styles.timeParts}>
          <select
            className={styles.timePart}
            aria-label="시"
            value={hour}
            disabled={busy}
            onChange={(event) => setTime(`${event.target.value}:${minute}`)}
          >
            {HOURS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
          <span aria-hidden="true">:</span>
          <select
            className={styles.timePart}
            aria-label="분"
            value={minute}
            disabled={busy}
            onChange={(event) => setTime(`${hour}:${event.target.value}`)}
          >
            {MINUTES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </span>
      </div>
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
        <button className={groupStyles.confirm} disabled={busy} onClick={() => onSave({ time, weekdays })}>
          {busy ? "저장 중…" : "저장"}
        </button>
      </div>
    </ConfirmDialog>
  );
}
