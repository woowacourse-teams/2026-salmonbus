import { useId, type ReactNode } from "react";
import {
  BOARD_SHEET_EXPANDED_PERCENT,
  boardSheetHeightsFor,
  type BoardSheetSnap,
} from "@/shared/bottom-sheet/boardSheetPolicy";
import { useBoardSheet } from "@/shared/bottom-sheet/useBoardSheet";
import * as styles from "./BoardBottomSheet.css";

interface BoardBottomSheetProps {
  id: string;
  snap: BoardSheetSnap;
  onSnapChange: (snap: BoardSheetSnap) => void;
  viewportHeight: number;
  enteringFromMap?: boolean;
  mapHeader: ReactNode;
  fullHeader: ReactNode;
  header?: ReactNode;
  children: ReactNode;
}

const SNAP_LABEL: Record<BoardSheetSnap, string> = {
  minimum: "노선 정보만 표시",
  half: "요약 표시",
  expanded: `화면 ${BOARD_SHEET_EXPANDED_PERCENT}% 표시`,
  full: "전체 화면 표시",
};
const SNAP_VALUE: Record<BoardSheetSnap, number> = { minimum: 0, half: 1, expanded: 1, full: 2 };

export function BoardBottomSheet({
  id,
  snap,
  onSnapChange,
  viewportHeight,
  enteringFromMap = false,
  mapHeader,
  fullHeader,
  header,
  children,
}: BoardBottomSheetProps) {
  const stationSummary = snap === "half";
  const listId = useId();
  const {
    sheetRef,
    headerRef,
    safeTopRef,
    safeBottomRef,
    height,
    heights,
    dragging,
    dragHandlers,
    onHandleKeyDown,
    onHandleClick,
  } = useBoardSheet({
    snap,
    onSnapChange: (next) => onSnapChange(next === "half" && !stationSummary ? "minimum" : next),
    viewportHeight,
    heightsFor: (measurements) => {
      const heights = boardSheetHeightsFor(measurements);
      heights.half = stationSummary
        ? Math.min(heights.full, Math.max(heights.minimum, viewportHeight * 0.3))
        : heights.minimum;
      return heights;
    },
  });
  const isFullHeight = snap === "full" && height === heights.full;

  return (
    <>
      {!isFullHeight && mapHeader}
      <section
        id={id}
        ref={sheetRef}
        tabIndex={-1}
        className={styles.sheet}
        style={{ height }}
        data-snap={snap}
        data-dragging={dragging}
        data-entering-from-map={enteringFromMap}
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
            aria-valuemax={2}
            aria-valuenow={SNAP_VALUE[snap]}
            aria-valuetext={SNAP_LABEL[snap]}
            aria-controls={listId}
            onKeyDown={onHandleKeyDown}
            onClick={onHandleClick}
          >
            <span className={styles.handleBar} />
          </div>
          {/* 노선 번호에서 시작한 드래그가 끝날 때까지 포인터를 잡은 요소를 유지한다. */}
          {snap === "full" && <div hidden={!isFullHeight}>{fullHeader}</div>}
          {header}
        </div>
        <div id={listId} className={styles.body} aria-hidden={snap === "minimum" && !dragging}>
          {children}
        </div>
      </section>
    </>
  );
}
