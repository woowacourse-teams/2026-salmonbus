import * as styles from "./DirectionLabel.css";

const LONG_LABEL_LENGTH = 18;
const TERMINAL_DETAILS = /^(.*?)(\s*\([^()]+\)(?:\s*\([^()]+\))*(?: 방면)?)$/u;

export function splitDirectionLabel(label: string) {
  const parts = label.length >= LONG_LABEL_LENGTH ? TERMINAL_DETAILS.exec(label) : null;
  return { name: parts?.[1] || label, details: parts?.[1] ? parts[2] : undefined };
}

export function DirectionLabel({ label, className }: { label: string; className?: string }) {
  const { name, details } = splitDirectionLabel(label);

  return (
    <span className={className}>
      {name}
      {details && <span className={styles.details}>{details}</span>}
    </span>
  );
}
