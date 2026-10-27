// TODO(SAL-231): 2026-12-01이 지나면 이 파일과 테스트, e2e의 출시 기간 테스트를 지우고 newRoutePolicy.ts의 knownBeforeIds를 inServiceIds로 바꾼다.
const LAUNCH_DEADLINE = Date.parse("2026-12-01T00:00:00+09:00");

const ROUTE_1650 = "234000050";
const ROUTE_3330 = "204000057";
const LAUNCH_KNOWN_ROUTE_IDS: readonly string[] = [ROUTE_1650, ROUTE_3330];

export function launchKnownRouteIdsFor(now: number): readonly string[] | null {
  return now < LAUNCH_DEADLINE ? LAUNCH_KNOWN_ROUTE_IDS : null;
}
