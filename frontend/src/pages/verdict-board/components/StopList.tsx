import type { StopView } from "../displayPolicy";
import { StopRow } from "./StopRow";
import type { RoutePosition } from "./TimelineMarker";
import * as styles from "./StopList.css";

interface StopListProps {
  stops: StopView[];
}

function routePositionFor(index: number, lastIndex: number): RoutePosition {
  if (lastIndex === 0) return "only";
  if (index === 0) return "start";
  if (index === lastIndex) return "end";
  return "middle";
}

export function StopList({ stops }: StopListProps) {
  return (
    <ul className={styles.list}>
      {stops.map((stop, index) => (
        <StopRow key={stop.stopId} stop={stop} routePosition={routePositionFor(index, stops.length - 1)} />
      ))}
    </ul>
  );
}
