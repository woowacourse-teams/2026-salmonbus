import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { isInService } from "../routeSearchPolicy";
import { launchKnownRouteIdsFor } from "./newRouteLaunch";

export type FirstSeenTimes = ReadonlyMap<string, number>;

const KNOWN_BEFORE = 0;
const NEW_PERIOD_MS = 7 * 24 * 60 * 60_000;

export function firstSeenTimesFor(
  routes: readonly RouteSummary[],
  previous: FirstSeenTimes,
  now: number,
): FirstSeenTimes {
  const inServiceIds = routes.filter(isInService).map((route) => route.id);
  const isFirstVisit = previous.size === 0;
  const times = new Map(previous);
  if (isFirstVisit) {
    const knownBeforeIds = launchKnownRouteIdsFor(now) ?? inServiceIds;
    for (const id of knownBeforeIds) times.set(id, KNOWN_BEFORE);
  }
  for (const id of inServiceIds) {
    if (!times.has(id)) times.set(id, now);
  }
  return times;
}

export function newRouteIdsFor(times: FirstSeenTimes, now: number): ReadonlySet<string> {
  const newIds = [...times]
    .filter(([, seenAt]) => {
      const elapsed = now - seenAt;
      return elapsed >= 0 && elapsed < NEW_PERIOD_MS;
    })
    .map(([id]) => id);
  return new Set(newIds);
}
