import type { ListedRoute } from "@/shared/route-list/newRoutePolicy";
import { NEW_BADGE, routeButtonLabelOf } from "@/shared/route-list/routeListCopy";
import * as styles from "./RouteRow.css";

interface RouteRowProps {
  route: ListedRoute;
  onSelect: () => void;
}

export function RouteRow({ route, onSelect }: RouteRowProps) {
  return (
    <button className={styles.row} type="button" aria-label={routeButtonLabelOf(route)} onClick={onSelect}>
      <span className={styles.routeNumber}>{route.displayName}</span>
      <span className={styles.stops}>
        {route.startStopName} ↔ {route.endStopName}
      </span>
      {route.isNew && (
        <span className={styles.newBadge} data-new-badge>
          {NEW_BADGE}
        </span>
      )}
    </button>
  );
}
