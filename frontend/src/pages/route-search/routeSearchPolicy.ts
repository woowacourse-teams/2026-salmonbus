import type { RouteSummary } from "@/shared/api/routeForecast.types";

export interface InServiceRoute extends RouteSummary {
  status: "FORECAST_READY";
}

export function isInService(route: RouteSummary): route is InServiceRoute {
  return route.status === "FORECAST_READY";
}

export function searchDigitsFrom(text: string): string {
  return text.replace(/\D/g, "");
}

export function matchingRoutesFor<T extends Pick<RouteSummary, "displayName">>(
  routes: readonly T[],
  input: string,
): T[] {
  const digits = searchDigitsFrom(input);
  if (digits === "") return [];
  return routes.filter((route) => searchDigitsFrom(route.displayName).startsWith(digits));
}
