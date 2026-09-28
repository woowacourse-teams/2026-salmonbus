import { useNavigate } from "react-router";
import { track } from "@/shared/analytics/track";
import { boardPathFor } from "@/shared/routing/paths";
import type { RouteCore } from "../api/routeSelect.type";
import { RouteListItem } from "./RouteListItem";
import * as styles from "../RouteSelectPage.css";

interface RouteListProps {
  routes: RouteCore[];
}

export function RouteList({ routes }: RouteListProps) {
  const navigate = useNavigate();
  const handleSelect = (route: RouteCore, index: number) => {
    track("route_selected", {
      route_id: route.id,
      route_status: route.status,
      list_position: index,
      route_count: routes.length,
    });
    void navigate(boardPathFor(route.id));
  };

  return (
    <ul className={styles.routeList}>
      {routes.map((route, index) => (
        <RouteListItem key={route.id} route={route} onSelect={() => handleSelect(route, index)} />
      ))}
    </ul>
  );
}
