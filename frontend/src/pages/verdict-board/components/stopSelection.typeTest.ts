import type { StopSelection } from "./stopSelection";
import type { RouteMapStop } from "../map/routeMapModel";

const selection = { direction: "UP", sequence: 1 } satisfies StopSelection;
// @ts-expect-error 방향 없이 순번만 있으면 양방향 지도에서 정류장을 특정할 수 없다.
const partialSelection: StopSelection = { sequence: 1 };
// @ts-expect-error 좌표가 없는 정류장을 준비된 지도 마커로 전달할 수 없다.
const unpositionedStop: RouteMapStop = { sequence: 1, direction: "UP", stopId: "stop-1" };

void selection;
void partialSelection;
void unpositionedStop;
