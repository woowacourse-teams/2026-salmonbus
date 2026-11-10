import * as styles from "./RetryButton.css";

interface RetryButtonProps {
  children: string;
  onRetry: () => void;
  busy?: boolean;
}

export function RetryButton({ children, onRetry, busy = false }: RetryButtonProps) {
  return (
    <button type="button" className={styles.button} onClick={onRetry} disabled={busy} aria-busy={busy}>
      <svg
        className={busy ? styles.spinning : undefined}
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="#D65B46"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M21 12a9 9 0 1 1-9-9 9.75 9.75 0 0 1 6.74 2.74L21 8m0-5v5h-5" />
      </svg>
      {children}
    </button>
  );
}
