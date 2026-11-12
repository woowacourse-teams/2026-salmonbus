import { createContext } from "react";
import type { NaverMap, NaverMaps } from "./naverSdk";

export interface MapFlowSurface {
  map: NaverMap;
  maps: NaverMaps;
  element: HTMLDivElement;
}

export type MapFlowSurfaceState =
  | { status: "loading" | "error"; surface: null }
  | { status: "ready"; surface: MapFlowSurface }
  | { status: "ready"; surface: null; native: true };

export const MapFlowSurfaceContext = createContext<MapFlowSurfaceState | null>(null);
export const NativeMapFlowContext = createContext<((boardActive: boolean) => void) | null>(null);
