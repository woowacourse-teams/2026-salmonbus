import { RECONNECT_LABEL, connectionNotice, type StatusNotice, type StatusTone } from "../chatCopy";
import type { ConnectionState } from "../connectionPolicy";
import * as styles from "./StatusBanner.css";

interface StatusBannerProps {
  notice: StatusNotice | null;
  connection: ConnectionState;
  onReconnect: () => void;
}

interface StatusIconProps {
  tone: StatusTone;
}

export function StatusBanner({ notice, connection, onReconnect }: StatusBannerProps) {
  const status = notice ?? connectionNotice(connection);
  if (status === null) return null;

  return (
    <div className={styles.banner} role="status" data-tone={status.tone}>
      <StatusIcon tone={status.tone} />
      <span>{status.text}</span>
      {connection === "failed" && (
        <button type="button" className={styles.reconnectButton} onClick={onReconnect}>
          {RECONNECT_LABEL}
        </button>
      )}
    </div>
  );
}

function StatusIcon({ tone }: StatusIconProps) {
  switch (tone) {
    case "progress":
      return (
        <svg className={styles.icon.progress} viewBox="0 0 24 24" aria-hidden="true">
          <path d="M20 12a8 8 0 1 1-5.5-7.6" />
        </svg>
      );
    case "success":
      return (
        <svg className={styles.icon.success} viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 12.5 9.5 17 19 7.5" />
        </svg>
      );
    case "error":
      return (
        <svg className={styles.icon.error} viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3.8 2.6 20h18.8L12 3.8Z" />
          <path d="M12 10v4.2m0 2.9h.01" />
        </svg>
      );
  }
}
