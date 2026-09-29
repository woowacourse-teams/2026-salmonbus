import type { ReactNode, RefObject } from "react";
import { sheetLabel } from "../chatCopy";
import type { ConnectionState } from "../connectionPolicy";
import type { SheetPosition } from "../sheetPolicy";
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
  onMove: (next: SheetPosition) => void;
  onToggleSize: () => void;
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
  onMove,
  onToggleSize,
  children,
}: ChatSheetProps) {
  const dragHandlers = useSheetDrag({ sheetRef, position, enabled: dragEnabled, onSettle: onMove });

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
      <div className={styles.content[position]}>
        <SheetHeader
          position={position}
          displayName={displayName}
          nickname={nickname}
          dragHandlers={dragHandlers}
          onToggleSize={onToggleSize}
          onCollapse={() => onMove("collapsed")}
        />
        {children}
      </div>
    </section>
  );
}
