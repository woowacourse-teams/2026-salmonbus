import { newMessagesLabel } from "../chatCopy";
import * as styles from "./NewMessagesButton.css";

interface NewMessagesButtonProps {
  count: number;
  onClick: () => void;
}

export function NewMessagesButton({ count, onClick }: NewMessagesButtonProps) {
  return (
    <button type="button" className={styles.button} onClick={onClick}>
      <ArrowDownIcon /> {newMessagesLabel(count)}
    </button>
  );
}

function ArrowDownIcon() {
  return (
    <svg className={styles.icon} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 5v14m6-6-6 6-6-6" />
    </svg>
  );
}
