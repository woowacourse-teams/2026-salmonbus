import type { ListedRoute } from "./newRoutePolicy";

export const NEW_BADGE = "NEW";
const NEW_ROUTE_LABEL = "새 노선";

export function routeButtonLabelOf(route: ListedRoute): string {
  const newRouteLabel = route.isNew ? `${NEW_ROUTE_LABEL}, ` : "";
  return `${route.displayName}번 노선, ${newRouteLabel}${route.startStopName}부터 ${route.endStopName} 구간`;
}
