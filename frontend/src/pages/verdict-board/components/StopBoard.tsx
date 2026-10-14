import { useRef } from "react";
import type { StopView } from "../displayPolicy";
import type { LiveVehicleView } from "../liveVehiclePolicy";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import { LOADING_NOTICE, LOAD_FAILED_NOTICE, OUT_OF_SERVICE_NOTICE } from "../verdictCopy";
import { StopList } from "./StopList";
import * as styles from "./StopBoard.css";

type NoticeStatus = "loading" | "error" | "outOfService";

type StopBoardProps =
  | { status: NoticeStatus }
  | {
      status: "ready";
      stops: StopView[];
      liveVehicleViews: LiveVehicleView[];
      motion: LiveVehicleMotionStore;
      entering: boolean;
      selectedSequence: number | null;
      selectionRevision: number;
      onToggle: (sequence: number) => void;
    };

const NOTICES: Record<NoticeStatus, string> = {
  loading: LOADING_NOTICE,
  error: LOAD_FAILED_NOTICE,
  outOfService: OUT_OF_SERVICE_NOTICE,
};

export function StopBoard(props: StopBoardProps) {
  const scrollContainerRef = useRef<HTMLElement | null>(null);
  if (props.status === "ready") {
    return (
      <section ref={scrollContainerRef} className={styles.board} role="region" aria-label="판정보드 정류장 목록">
        <StopList
          stops={props.stops}
          liveVehicleViews={props.liveVehicleViews}
          motion={props.motion}
          entering={props.entering}
          selectedSequence={props.selectedSequence}
          selectionRevision={props.selectionRevision}
          onToggle={props.onToggle}
          scrollContainerRef={scrollContainerRef}
        />
      </section>
    );
  }

  return (
    <section className={styles.noticeBoard} role="region" aria-label="판정보드 정류장 목록">
      <p className={styles.notice}>{NOTICES[props.status]}</p>
    </section>
  );
}
