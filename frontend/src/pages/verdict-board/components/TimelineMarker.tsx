import type { SeatLevel } from "../seatGrade";
import * as styles from "./TimelineMarker.css";

export type RoutePosition = "start" | "middle" | "end" | "only";
export type MarkerTone = SeatLevel | "noForecast" | "passThrough";
export type MarkerBand = "stop" | "waypoint";

interface TimelineMarkerProps {
  sequence: number;
  routePosition: RoutePosition;
  tone: MarkerTone;
  band: MarkerBand;
}

interface HeadingArrowProps {
  tone: Exclude<MarkerTone, "passThrough">;
}

export function TimelineMarker({ sequence, routePosition, tone, band }: TimelineMarkerProps) {
  const headHidden = routePosition === "start" || routePosition === "only";
  const tailHidden = routePosition === "end" || routePosition === "only";

  return (
    <div className={styles.track} aria-hidden="true">
      <span className={headHidden ? styles.hiddenHeadSegment[band] : styles.headSegment[band]} />
      <span className={styles.ring[tone]} data-timeline-sequence={sequence}>
        {tone !== "passThrough" && <HeadingArrow tone={tone} />}
      </span>
      <span className={tailHidden ? styles.hiddenTailSegment : styles.tailSegment} />
    </div>
  );
}

function HeadingArrow({ tone }: HeadingArrowProps) {
  return (
    <svg
      className={styles.heading[tone]}
      width="8"
      height="8"
      viewBox="0 0 8 8"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M1.6 3 4 5.4 6.4 3" />
    </svg>
  );
}
