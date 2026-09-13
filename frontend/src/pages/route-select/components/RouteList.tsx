import { useNavigate } from "react-router";
import { boardPathFor } from "@/shared/routing/paths";
import type { RouteCore } from "../api/routeSelect.type";
import { RouteListItem } from "./RouteListItem";
import * as styles from "../RouteSelectPage.css";

interface RouteListProps {
  routes: RouteCore[];
}

export function RouteList({ routes }: RouteListProps) {
  const navigate = useNavigate();
  const handleSelect = (routeId: RouteCore["id"]) => {
    void navigate(boardPathFor(routeId));
  };

  return (
    <ul className={styles.routeList}>
      {routes.map((route) => (
        <RouteListItem key={route.id} route={route} onSelect={() => handleSelect(route.id)} />
      ))}
    </ul>
  );
}
