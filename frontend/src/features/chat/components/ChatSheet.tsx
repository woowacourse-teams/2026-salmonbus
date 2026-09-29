import type { ReactNode, RefObject } from "react";
import { sheetLabel } from "../chatCopy";
import { isConnectionStalled, type ConnectionState } from "../connectionPolicy";
import type { SheetLayout, SheetPosition } from "../sheetPolicy";
import { useSheetDrag } from "../useSheetDrag";
import { SheetHeader } from "./SheetHeader";
import * as styles from "./ChatSheet.css";

type SheetConnection = ConnectionState | "idle";

interface ChatSheetProps {
  sheetRef: RefObject<HTMLElement | null>;
  id: string;
  position: SheetPosition;
  modal: boolean;
  connection: SheetConnection;
  dragEnabled: boolean;
  displayName: string;
  nickname: string | null;
  onSettle: (next: SheetLayout) => void;
  onToggleSize: () => void;
  onCollapse: () => void;
  children: ReactNode;
}

export function ChatSheet({
  sheetRef,
  id,
  position,
  modal,
  connection,
  dragEnabled,
  displayName,
  nickname,
  onSettle,
  onToggleSize,
  onCollapse,
  children,
}: ChatSheetProps) {
  const dragHandlers = useSheetDrag({ sheetRef, position, enabled: dragEnabled, onSettle });

  return (
    <section
      ref={sheetRef}
      id={id}
      className={styles.sheet[position]}
      role="dialog"
      aria-modal={modal}
      aria-label={sheetLabel(displayName)}
      tabIndex={-1}
      inert={position === "collapsed"}
      data-position={position}
      data-connection={connection}
    >
      <div className={styles.content}>
        <SheetHeader
          position={position}
          displayName={displayName}
          nickname={nickname}
          stalled={isConnectionStalled(connection)}
          dragHandlers={dragHandlers}
          onToggleSize={onToggleSize}
          onCollapse={onCollapse}
        />
        {children}
      </div>
    </section>
  );
}
