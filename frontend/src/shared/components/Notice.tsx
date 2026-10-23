import { useEffect, useState } from "react";
import * as styles from "./Notice.css";

export function Notice({ message }: { message: string }) {
  const [dismissed, setDismissed] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setDismissed(true), 4_000);
    return () => window.clearTimeout(timer);
  }, []);
  return dismissed ? null : (
    <div className={styles.toast} role="status">
      {message}
    </div>
  );
}
