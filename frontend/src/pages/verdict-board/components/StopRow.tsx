import { useId } from "react";
import type { ExpandableStopView, StopView } from "./displayPolicy";
import { stopNameLabel, WAYPOINT_CAPTION } from "./verdictCopy";
import { ArrivalForecastCard } from "./ArrivalForecastCard";
import type { StationArrivalDetails } from "./stationArrivalPolicy";
import { StopName } from "./StopName";
import { TimelineMarker, type RoutePosition } from "./TimelineMarker";
import { VerdictChip } from "./VerdictChip";
import * as styles from "./StopRow.css";

type ChevronDirection = "right" | "up";

interface StopRowProps {
  stop: StopView;
  routePosition: RoutePosition;
  selected: boolean;
  expanded: boolean;
  stationArrivals: StationArrivalDetails | null;
  onToggle: (stop: ExpandableStopView) => void;
}

interface ChevronProps {
  direction: ChevronDirection;
}

export function StopRow({ stop, routePosition, selected, expanded, stationArrivals, onToggle }: StopRowProps) {
  const panelId = useId();

  if (stop.kind === "passThrough") {
    return (
      <li className={styles.waypointRow} data-stop-sequence={stop.sequence} data-selected={selected} tabIndex={-1}>
        <TimelineMarker sequence={stop.sequence} routePosition={routePosition} tone="passThrough" band="waypoint" />
        <div className={styles.waypointHead}>
          <StopName name={stopNameLabel(stop)} muted />
          <span className={styles.caption}>{WAYPOINT_CAPTION}</span>
        </div>
      </li>
    );
  }

  const boardingStop = stop.kind === "boarding" ? stop : null;
  const tone = boardingStop === null ? "noVehicle" : boardingStop.tone;

  return (
    <li className={styles.stopRow} data-stop-sequence={stop.sequence} data-selected={selected}>
      <TimelineMarker sequence={stop.sequence} routePosition={routePosition} tone={tone} band="stop" />
      <button
        type="button"
        className={expanded ? styles.head.expanded : styles.head.collapsed}
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={() => onToggle(stop)}
      >
        <span className={styles.headStack}>
          <StopName name={stopNameLabel(stop)} />
          <span className={expanded ? styles.chipSlot.hidden : styles.chipSlot.shown} aria-hidden={expanded}>
            <VerdictChip tone={tone} />
          </span>
        </span>
        <Chevron direction={expanded ? "up" : "right"} />
      </button>
      <div
        className={expanded ? styles.detail.open : styles.detail.closed}
        inert={!expanded}
        onClick={expanded ? () => onToggle(stop) : undefined}
      >
        <div id={panelId} className={styles.detailInner}>
          <ArrivalForecastCard arrivals={boardingStop?.arrivals ?? []} stationArrivals={stationArrivals} />
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
