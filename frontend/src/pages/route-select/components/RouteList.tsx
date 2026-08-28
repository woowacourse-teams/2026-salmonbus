import { useNavigate } from "react-router";
import { boardPathFor } from "@/shared/routing/paths";
import type { RouteCore } from "../api/routeSelect.type";
import { RouteListItem } from "./RouteListItem";

interface RouteListProps {
  routes: RouteCore[];
}

export function RouteList({ routes }: RouteListProps) {
  const navigate = useNavigate();
  const handleSelect = (routeId: RouteCore["id"]) => {
    void navigate(boardPathFor(routeId));
  };

  return (
    <ul>
      {routes.map((route) => (
        <RouteListItem key={route.id} route={route} onSelect={() => handleSelect(route.id)} />
      ))}
    </ul>
  );
}
