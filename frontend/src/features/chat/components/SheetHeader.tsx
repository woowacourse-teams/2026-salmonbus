import { CHAT_NAME, COLLAPSE_LABEL, nicknameLabel, sizeToggleLabel } from "../chatCopy";
import type { SheetPosition } from "../sheetPolicy";
import type { SheetDragHandlers } from "../useSheetDrag";
import * as styles from "./SheetHeader.css";

interface SheetHeaderProps {
  position: SheetPosition;
  displayName: string;
  nickname: string | null;
  stalled: boolean;
  dragHandlers: SheetDragHandlers;
  onToggleSize: (restoreFocus: boolean) => void;
  onCollapse: (restoreFocus: boolean) => void;
}

export function SheetHeader({
  position,
  displayName,
  nickname,
  stalled,
  dragHandlers,
  onToggleSize,
  onCollapse,
}: SheetHeaderProps) {
  const subtitle = nicknameLabel(nickname, stalled);

  return (
    <header className={styles.header} {...dragHandlers}>
      <div className={styles.grabberRow[position]}>
        <button
          type="button"
          className={styles.grabber}
          aria-label={sizeToggleLabel(position)}
          onClick={(event) => onToggleSize(event.detail === 0)}
        >
          <span className={styles.grabberBar} aria-hidden="true" />
        </button>
      </div>
      <div className={styles.titleRow}>
        <div className={styles.titleBlock}>
          <h2 className={styles.title}>
            <span>{displayName}</span> {CHAT_NAME}
          </h2>
          {subtitle !== null && <p className={styles.subtitle}>{subtitle}</p>}
        </div>
        <button
          type="button"
          className={styles.collapseButton}
          aria-label={COLLAPSE_LABEL}
          onClick={(event) => onCollapse(event.detail === 0)}
        >
          <ChevronDownIcon />
        </button>
      </div>
    </header>
  );
}

function ChevronDownIcon() {
  return (
    <svg className={styles.collapseIcon} viewBox="0 0 24 24" aria-hidden="true">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
