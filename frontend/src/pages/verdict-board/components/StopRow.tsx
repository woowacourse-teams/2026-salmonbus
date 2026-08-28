import { WAITING_LABELS, type StopView } from "../displayPolicy";
import { BoardingChip } from "./BoardingChip";
import { StopName } from "./StopName";
import { TimelineMarker, type RoutePosition } from "./TimelineMarker";
import * as styles from "./StopRow.css";

interface StopRowProps {
  stop: StopView;
  routePosition: RoutePosition;
}

export function StopRow({ stop, routePosition }: StopRowProps) {
  if (stop.kind === "passThrough") {
    return (
      <li className={styles.passThroughRow}>
        <div className={styles.nameCell}>
          <StopName name={stop.name} muted />
          <span className={styles.passThroughCaption}>경유</span>
        </div>
        <TimelineMarker routePosition={routePosition} tone="passThrough" />
      </li>
    );
  }

  if (stop.kind === "waiting") {
    return (
      <li className={styles.passThroughRow}>
        <div className={styles.nameCell}>
          <StopName name={stop.name} />
          <span className={styles.passThroughCaption}>{WAITING_LABELS[stop.reason]}</span>
        </div>
        <TimelineMarker routePosition={routePosition} tone="passThrough" />
      </li>
    );
  }

  return (
    <li className={styles.boardingRow[stop.level]}>
      <div className={styles.nameCell}>
        <StopName name={stop.name} />
      </div>
      <TimelineMarker routePosition={routePosition} tone={stop.level} />
      <div className={styles.verdictCell}>
        <BoardingChip level={stop.level} />
      </div>
    </li>
  );
}
