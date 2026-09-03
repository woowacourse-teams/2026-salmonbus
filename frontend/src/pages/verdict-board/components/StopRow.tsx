import { useId } from "react";
import type { StopView } from "../displayPolicy";
import { stopNameLabel, WAYPOINT_CAPTION } from "../verdictCopy";
import { ArrivalForecastCard } from "./ArrivalForecastCard";
import { StopName } from "./StopName";
import { TimelineMarker, type RoutePosition } from "./TimelineMarker";
import { VerdictChip } from "./VerdictChip";
import * as styles from "./StopRow.css";

type ChevronDirection = "right" | "up";

interface StopRowProps {
  stop: StopView;
  routePosition: RoutePosition;
  expanded: boolean;
  onToggle: () => void;
}

interface ChevronProps {
  direction: ChevronDirection;
}

export function StopRow({ stop, routePosition, expanded, onToggle }: StopRowProps) {
  const panelId = useId();

  if (stop.kind === "passThrough") {
    return (
      <li className={styles.waypointRow}>
        <TimelineMarker routePosition={routePosition} tone="passThrough" band="waypoint" />
        <div className={styles.waypointHead}>
          <StopName name={stopNameLabel(stop)} muted />
          <span className={styles.caption}>{WAYPOINT_CAPTION}</span>
        </div>
      </li>
    );
  }

  const forecast = stop.kind === "boarding" ? stop : null;

  return (
    <li className={styles.stopRow}>
      <TimelineMarker
        routePosition={routePosition}
        tone={forecast === null ? "noForecast" : forecast.level}
        band="stop"
      />
      <button
        type="button"
        className={expanded ? styles.head.expanded : styles.head.collapsed}
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={onToggle}
      >
        <span className={styles.headStack}>
          <StopName name={stopNameLabel(stop)} />
          <span className={expanded ? styles.chipSlot.hidden : styles.chipSlot.shown} aria-hidden={expanded}>
            <VerdictChip tone={forecast === null ? "noForecast" : forecast.level} />
          </span>
        </span>
        <Chevron direction={expanded ? "up" : "right"} />
      </button>
      <div
        className={expanded ? styles.detail.open : styles.detail.closed}
        inert={!expanded}
        onClick={expanded ? onToggle : undefined}
      >
        <div className={styles.detailInner}>
          <ArrivalForecastCard id={panelId} arrivals={forecast?.arrivals ?? []} />
        </div>
      </div>
    </li>
  );
}

function Chevron({ direction }: ChevronProps) {
  return (
    <svg
      className={styles.chevron[direction]}
      width="18"
      height="18"
      viewBox="0 0 18 18"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M5 7 9 11l4-4" />
    </svg>
  );
}
