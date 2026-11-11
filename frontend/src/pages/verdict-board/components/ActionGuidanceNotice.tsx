import * as styles from "./ActionGuidanceNotice.css";

export function ActionGuidanceNotice({ line }: { line: string }) {
  return (
    <p className={styles.notice} role="status">
      {line}
    </p>
  );
}
