import { createContext } from "react";
import type { NaverMap, NaverMaps } from "./naverSdk";

export interface MapFlowSurface {
  map: NaverMap;
  maps: NaverMaps;
  element: HTMLDivElement;
}

export type MapFlowSurfaceState =
  { status: "loading" | "error"; surface: null } | { status: "ready"; surface: MapFlowSurface };

export const MapFlowSurfaceContext = createContext<MapFlowSurfaceState | null>(null);
