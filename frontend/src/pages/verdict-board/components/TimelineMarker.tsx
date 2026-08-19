import type { SeatLevel } from "../displayPolicy";
import * as styles from "./TimelineMarker.css";

export type RoutePosition = "start" | "middle" | "end" | "only";
export type MarkerTone = SeatLevel | "passThrough";

interface TimelineMarkerProps {
  routePosition: RoutePosition;
  tone: MarkerTone;
}

export function TimelineMarker({ routePosition, tone }: TimelineMarkerProps) {
  const hideTopLine = routePosition === "start" || routePosition === "only";
  const hideBottomLine = routePosition === "end" || routePosition === "only";

  return (
    <div className={styles.track} aria-hidden="true">
      <span className={hideTopLine ? styles.hiddenLine : styles.line} />
      <span className={styles.dot[tone]} />
      <span className={hideBottomLine ? styles.hiddenLine : styles.line} />
    </div>
  );
}
