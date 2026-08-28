import type { RouteCore } from "../api/routeSelect.type";
import { RouteListItem } from "./RouteListItem";

interface RouteListProps {
  routes: RouteCore[];
}

export function RouteList({ routes }: RouteListProps) {
  const handleSelect = (routeId: RouteCore["id"]) => {
    void routeId;
    //TODO: SAL-53에서 판정 보드 이동 로직 구현 예정
  };

  return (
    <ul>
      {routes.map((route) => (
        <RouteListItem key={route.id} route={route} onSelect={() => handleSelect(route.id)} />
      ))}
    </ul>
  );
}
