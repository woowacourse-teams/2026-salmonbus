import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { matchingRoutesFor } from "./routeSearchPolicy";

export type RoutesState = { status: "loading" } | { status: "error" } | { status: "ready"; routes: RouteSummary[] };

export type SearchView =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "matched"; routes: RouteSummary[] }
  | { kind: "unmatched" };

export function searchViewFor(routesState: RoutesState, input: string): SearchView {
  if (routesState.status === "error") return { kind: "error" };
  if (input.trim() === "") return { kind: "idle" };
  if (routesState.status === "loading") return { kind: "loading" };
  const matched = matchingRoutesFor(routesState.routes, input);
  return matched.length > 0 ? { kind: "matched", routes: matched } : { kind: "unmatched" };
}

export function submitTargetFor(view: SearchView): RouteSummary | null {
  if (view.kind !== "matched" || view.routes.length !== 1) return null;
  return view.routes[0] ?? null;
}
