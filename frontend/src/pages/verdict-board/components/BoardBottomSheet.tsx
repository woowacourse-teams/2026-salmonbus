import { useId, type ReactNode } from "react";
import type { BoardSheetSnap } from "./boardSheetPolicy";
import { useBoardSheet } from "../hooks/useBoardSheet";
import * as styles from "./BoardBottomSheet.css";

interface BoardBottomSheetProps {
  snap: BoardSheetSnap;
  onSnapChange: (snap: BoardSheetSnap) => void;
  viewportHeight: number;
  header: ReactNode;
  children: ReactNode;
}

const SNAP_LABEL: Record<BoardSheetSnap, string> = {
  minimum: "노선 정보만 표시",
  half: "화면 절반 표시",
  expanded: "화면 70% 표시",
  full: "전체 화면 표시",
};
const SNAP_VALUE: Record<BoardSheetSnap, number> = { minimum: 0, half: 1, expanded: 2, full: 3 };

export function BoardBottomSheet({ snap, onSnapChange, viewportHeight, header, children }: BoardBottomSheetProps) {
  const listId = useId();
  const {
    sheetRef,
    headerRef,
    safeTopRef,
    safeBottomRef,
    height,
    dragging,
    dragHandlers,
    onHandleKeyDown,
    onHandleClick,
  } = useBoardSheet({ snap, onSnapChange, viewportHeight });

  return (
    <section
      ref={sheetRef}
      className={styles.sheet}
      style={{ height }}
      data-snap={snap}
      data-dragging={dragging}
      data-native-map-hit
      aria-label="판정보드"
    >
      <span ref={safeTopRef} className={styles.safeTop} aria-hidden="true" />
      <span ref={safeBottomRef} className={styles.safeBottom} aria-hidden="true" />
      <div ref={headerRef} className={styles.header} data-native-map-drag {...dragHandlers}>
        <div
          className={styles.handle}
          role="slider"
          tabIndex={0}
          aria-label="판정보드 높이"
          aria-orientation="vertical"
          aria-valuemin={0}
          aria-valuemax={3}
          aria-valuenow={SNAP_VALUE[snap]}
          aria-valuetext={SNAP_LABEL[snap]}
          aria-controls={listId}
          onKeyDown={onHandleKeyDown}
          onClick={onHandleClick}
        >
          <span className={styles.handleBar} />
        </div>
        {header}
      </div>
      <div id={listId} className={styles.body} aria-hidden={snap === "minimum" && !dragging}>
        {children}
      </div>
    </section>
  );
}
