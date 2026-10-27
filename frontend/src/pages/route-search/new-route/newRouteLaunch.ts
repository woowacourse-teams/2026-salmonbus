// 출시 기간에 처음 온 기기는 이 노선들만 원래 알던 노선으로 두고, 나머지 서비스 중 노선에 NEW를 붙인다.
// 마감이 지나면 이 파일과 테스트를 지우고 newRoutePolicy.ts에서 서비스 중 노선만 원래 알던 노선으로 적게 바꾼다.
const LAUNCH_DEADLINE = Date.parse("2026-12-01T00:00:00+09:00");

// 1650번, 3330번. 노선 이름은 바뀔 수 있어서 id로 적는다.
const LAUNCH_KNOWN_ROUTE_IDS: readonly string[] = ["234000050", "204000057"];

export function launchKnownRouteIdsFor(now: number): readonly string[] | null {
  return now < LAUNCH_DEADLINE ? LAUNCH_KNOWN_ROUTE_IDS : null;
}
