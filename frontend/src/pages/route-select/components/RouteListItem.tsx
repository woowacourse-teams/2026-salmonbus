import type { RouteCore } from "../api/routeSelect.type";
import * as styles from "../RouteSelectPage.css";

interface RouteListItemProps {
  route: RouteCore;
  onSelect: (routeId: RouteCore["id"]) => void;
}

export function RouteListItem({ route, onSelect }: RouteListItemProps) {
  return (
    <li className={styles.routeItem}>
      <button
        className={styles.routeButton}
        type="button"
        aria-label={`${route.displayName}번 노선, ${route.startStopName}부터 ${route.endStopName} 구간`}
        onClick={() => onSelect(route.id)}
      >
        <span className={styles.routeNumber}>{route.displayName}</span>
        <span className={styles.routeDirection}>
          {route.startStopName} ↔ {route.endStopName}
        </span>
      </button>
    </li>
  );
}
