import type { EventMap } from "@/shared/analytics/events";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import type { SearchView } from "../displayPolicy";
import { searchDigitsFrom } from "../routeSearchPolicy";

export function routeSelectedPropertiesOf(
  route: RouteSummary,
  position: number,
  resultCount: number,
): EventMap["route_selected"] {
  return {
    route_id: route.id,
    route_status: route.status,
    result_position: position,
    result_count: resultCount,
  };
}

export function unmatchedQueryOf(view: SearchView, input: string): string | null {
  if (view.kind !== "unmatched") return null;
  const query = searchDigitsFrom(input);
  return query === "" ? null : query;
}
