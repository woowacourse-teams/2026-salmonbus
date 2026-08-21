interface Route {
  id: string;
  displayName: string;
  startStopName: string;
  endStopName: string;
  status: "FORECAST_READY" | "PREPARING";
}

export interface Routes {
  routes: Route[];
}
