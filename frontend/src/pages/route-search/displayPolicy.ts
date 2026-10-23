import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { matchingRoutesFor } from "./routeSearchPolicy";

export type RoutesState = { status: "loading" } | { status: "error" } | { status: "ready"; routes: RouteSummary[] };

export type SearchView =
  | { kind: "empty" }
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "matched"; routes: RouteSummary[] }
  | { kind: "unmatched" };

export function searchViewFor(routesState: RoutesState, input: string): SearchView {
  if (routesState.status === "error") return { kind: "error" };
  if (input.trim() === "") return { kind: "empty" };
  if (routesState.status === "loading") return { kind: "loading" };
  const matched = matchingRoutesFor(routesState.routes, input);
  return matched.length > 0 ? { kind: "matched", routes: matched } : { kind: "unmatched" };
}

export interface RouteSelection {
  route: RouteSummary;
  position: number;
  listedCount: number;
}

export function submitSelectionFor(view: SearchView): RouteSelection | null {
  if (view.kind !== "matched" || view.routes.length !== 1) return null;
  const route = view.routes[0];
  return route ? { route, position: 0, listedCount: view.routes.length } : null;
}
