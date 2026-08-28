import type { RouteCore } from "../api/routeSelect.type";
interface RouteListItemProps {
  route: RouteCore;
  onSelect: (routeId: RouteCore["id"]) => void;
}

export function RouteListItem({ route, onSelect }: RouteListItemProps) {
  return (
    <li>
      <button
        type="button"
        aria-label={`${route.displayName}번 노선, ${route.startStopName}부터 ${route.endStopName} 구간`}
        onClick={() => onSelect(route.id)}
      >
        <span>{route.displayName}</span>
        <span>
          {route.startStopName} ↔ {route.endStopName}
        </span>
      </button>
    </li>
  );
}
