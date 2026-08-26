export type RouteStatus = "FORECAST_READY" | "PREPARING";

export interface RouteCore {
  id: string;
  displayName: string;
  startStopName: string;
  endStopName: string;
  status: RouteStatus;
}

export interface RouteListRespone {
  routes: RouteCore[];
}
