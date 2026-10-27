const LAUNCH_DEADLINE = Date.parse("2026-12-01T00:00:00+09:00");

const ROUTE_1650 = "234000050";
const ROUTE_3330 = "204000057";
const LAUNCH_KNOWN_ROUTE_IDS: readonly string[] = [ROUTE_1650, ROUTE_3330];

export function launchKnownRouteIdsFor(now: number): readonly string[] | null {
  return now < LAUNCH_DEADLINE ? LAUNCH_KNOWN_ROUTE_IDS : null;
}
