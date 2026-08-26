import type { RouteCore } from "../api/RouteSelect.type";
import { RouteListItem } from "./RouteListItem";

interface RouteListProps {
  routes: RouteCore[];
}

export function RouteList({ routes }: RouteListProps) {
  return (
    <ul>
      {routes.map((route) => (
        <RouteListItem
          displayName={route.displayName}
          startStopName={route.startStopName}
          endStopName={route.endStopName}
        />
      ))}
    </ul>
  );
}
