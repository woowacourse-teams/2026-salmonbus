import { useRef, type RefObject } from "react";
import { CHAT_NAME, launcherLabel } from "../chatCopy";
import { usePeekResize } from "../usePeekResize";
import * as styles from "./ChatPeek.css";

interface ChatPeekProps {
  launcherRef: RefObject<HTMLButtonElement | null>;
  sheetId: string;
  displayName: string;
  unread: number;
  teaser: string | null;
  onOpen: () => void;
}

const MAX_UNREAD_BADGE = 99;

export function ChatPeek({ launcherRef, sheetId, displayName, unread, teaser, onOpen }: ChatPeekProps) {
  const labelRef = useRef<HTMLSpanElement | null>(null);
  const label = teaser ?? CHAT_NAME;
  usePeekResize(launcherRef, labelRef, `${label}:${unread}`);

  return (
    <div className={styles.slot}>
      <button
        ref={launcherRef}
        type="button"
        className={styles.launcher[unread > 0 ? "unread" : "plain"]}
        aria-haspopup="dialog"
        aria-controls={sheetId}
        aria-label={launcherLabel(displayName, unread)}
        onClick={onOpen}
      >
        <MessageIcon />
        <span key={label} ref={labelRef} className={teaser === null ? styles.label : styles.teaser}>
          {label}
        </span>
        {unread > 0 && <span className={styles.unreadBadge}>{Math.min(unread, MAX_UNREAD_BADGE)}</span>}
      </button>
    </div>
  );
}

function MessageIcon() {
  return (
    <svg className={styles.icon} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.5 9.5 9.5 0 0 1-4-.9L3 21l1.6-4.6A8.4 8.4 0 1 1 21 11.5Z" />
    </svg>
  );
}
