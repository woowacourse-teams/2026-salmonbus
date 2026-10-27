import type { EventMap } from "@/shared/analytics/events";
import type { RouteSelection, SearchView } from "../displayPolicy";
import { searchDigitsFrom } from "../routeSearchPolicy";

export function routeSelectedPropertiesOf({
  route,
  position,
  listedCount,
}: RouteSelection): EventMap["route_selected"] {
  return {
    route_id: route.id,
    route_status: route.status,
    is_new: route.isNew,
    result_position: position,
    result_count: listedCount,
  };
}

export function unmatchedQueryOf(view: SearchView, input: string): string | null {
  if (view.kind !== "unmatched") return null;
  const query = searchDigitsFrom(input);
  return query === "" ? null : query;
}
