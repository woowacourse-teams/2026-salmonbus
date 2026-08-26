import type { RouteCore } from "./RouteSelect.type";

export const titleMock = "어떤 노선을 이용하시나요?";
export const captionMock = "노선을 선택하면 정류장별 탑승 확률과 \n도착예상 좌석을 확인할 수 있어요";
export const routesMock = [
  {
    id: "204000057",
    displayName: "3330",
    startStopName: "도촌동9단지앞",
    endStopName: "안양역",
    status: "PREPARING",
  },
  {
    id: "234000050",
    displayName: "1650",
    startStopName: "구리수택차고지",
    endStopName: "안양역",
    status: "FORECAST_READY",
  },
] satisfies RouteCore[];
