import { useId } from "react";
import type { ExpandableStopView, StopView } from "./displayPolicy";
import { NO_VEHICLE_NOTICE, STALE_FORECAST_NOTICE, stopNameLabel, WAYPOINT_CAPTION } from "./verdictCopy";
import { ArrivalForecastCard } from "./ArrivalForecastCard";
import type { StationArrivalDetails } from "./stationArrivalPolicy";
import { StopName } from "./StopName";
import { TimelineMarker, type RoutePosition } from "./TimelineMarker";
import { VerdictChip } from "@/shared/components/VerdictChip";
import * as styles from "./StopRow.css";
import { FavoriteButton, type FavoriteBoardRoute } from "./FavoriteButton";

type ChevronDirection = "right" | "up";

interface StopRowProps {
  stop: StopView;
  routePosition: RoutePosition;
  selected: boolean;
  expanded: boolean;
  stationArrivals: StationArrivalDetails | null;
  onToggle: (stop: ExpandableStopView) => void;
  favoriteRoute?: FavoriteBoardRoute;
}

interface ChevronProps {
  direction: ChevronDirection;
}

export function StopRow({
  stop,
  routePosition,
  selected,
  expanded,
  stationArrivals,
  onToggle,
  favoriteRoute,
}: StopRowProps) {
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

  const tone = stop.kind === "boarding" ? stop.tone : stop.kind;
  const emptyNotice = stop.kind === "checking" ? STALE_FORECAST_NOTICE : NO_VEHICLE_NOTICE;

  return (
    <li className={styles.stopRow} data-stop-sequence={stop.sequence} data-selected={selected}>
      <TimelineMarker sequence={stop.sequence} routePosition={routePosition} tone={tone} band="stop" />
      <button
        type="button"
        className={`${expanded ? styles.head.expanded : styles.head.collapsed} ${favoriteRoute ? styles.favoriteHead : ""}`}
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
        {!favoriteRoute && <Chevron direction={expanded ? "up" : "right"} />}
      </button>
      {favoriteRoute && (
        <FavoriteButton
          className={expanded ? styles.favorite.expanded : styles.favorite.collapsed}
          target={{ routeId: favoriteRoute.routeId, direction: favoriteRoute.direction, stopId: stop.stopId }}
          stopName={stop.name}
        />
      )}
      <div
        className={expanded ? styles.detail.open : styles.detail.closed}
        inert={!expanded}
        onClick={expanded ? () => onToggle(stop) : undefined}
      >
        <div id={panelId} className={styles.detailInner}>
          <ArrivalForecastCard
            arrivals={stop.kind === "boarding" ? stop.arrivals : []}
            stationArrivals={stationArrivals}
            emptyNotice={emptyNotice}
          />
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
