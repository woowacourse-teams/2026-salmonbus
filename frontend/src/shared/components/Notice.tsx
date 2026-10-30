import { useEffect, useState } from "react";
import * as styles from "./Notice.css";

export const NOTICE_DURATION_MS = 4_000;

export function Notice({ message }: { message: string }) {
  const [dismissed, setDismissed] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setDismissed(true), NOTICE_DURATION_MS);
    return () => window.clearTimeout(timer);
  }, []);
  return dismissed ? null : (
    <div className={styles.toast} role="status">
      {message}
    </div>
  );
}
