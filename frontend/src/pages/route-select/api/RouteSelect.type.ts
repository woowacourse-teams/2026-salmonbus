export type RouteStatus = "PREPARING" | "FORECAST_READY";

export interface RouteCore {
  id: string;
  displayName: string;
  startStopName: string;
  endStopName: string;
  status: RouteStatus;
}

export interface Routes {
  routes: Route[];
}
