import type { Direction } from "../api/routeForecast.types";

/** Versioned WebView ↔ native contract. Coordinates use WGS84; layout uses CSS pixels. */
export interface NativeCoordinate {
  latitude: number;
  longitude: number;
}
export interface NativeStopSelection {
  direction: Direction;
  sequence: number;
}
export interface NativeRect {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface NativeMapLayout {
  viewport: { width: number; height: number; offsetTop: number; offsetLeft: number; scale: number };
  mapRect: NativeRect;
  webRects: NativeRect[];
  captureAll: boolean;
  transitioning: boolean;
}
export interface NativeMapVehicle {
  key: string;
  direction: Direction;
  currentStopSequence: number;
  coordinate: NativeCoordinate;
  caption: string;
  label: string;
  remainingSeats: number | null;
}
export interface NativeMapSceneState {
  activeDirection: Direction;
  selectedStop: NativeStopSelection | null;
  selectedVehicleKey: string | null;
  vehicles: NativeMapVehicle[];
}
export interface NativeMapScene extends NativeMapSceneState {
  routeId: string;
  referenceVersionId: string;
  stops: (NativeStopSelection & {
    stopId: string;
    coordinate: NativeCoordinate;
    name: string;
    boardingAllowed: boolean;
    terminalStopName: string;
  })[];
  paths: { direction: Direction; coordinates: NativeCoordinate[] }[];
  fullPath: NativeCoordinate[];
}
export interface NativeVehicleMotion {
  key: string;
  signature: string;
  path: (NativeCoordinate & { progress: number })[];
  durationMs: number;
}
export type NativeCameraCommand =
  | { command: "stop"; selection: NativeStopSelection }
  | { command: "vehicle"; key: string | null; focusMarker: boolean }
  | { command: "location"; coordinate: NativeCoordinate };
export interface NativeMapCommands {
  mount: NativeMapScene;
  updateScene: NativeMapSceneState;
  motion: { vehicles: NativeVehicleMotion[] };
  layout: NativeMapLayout;
  camera: NativeCameraCommand;
  location: { requestId: number };
  unmount: Record<string, never>;
}
export interface NativeMapEvents {
  ready: Record<string, never>;
  ack: { revision: number };
  stopPress: { selection: NativeStopSelection };
  vehiclePress: { selection: NativeStopSelection; key: string };
  mapPress: Record<string, never>;
  locationResult: {
    requestId: number;
    coordinate?: NativeCoordinate;
    error?: "denied" | "timeout" | "unavailable";
  };
  error: { message: string };
}
export type NativeMapMessage<T extends keyof NativeMapCommands = keyof NativeMapCommands> = {
  [K in T]: { v: 1; sessionId: string; revision: number; type: K; payload: NativeMapCommands[K] };
}[T];
export type NativeMapEvent = {
  [K in keyof NativeMapEvents]: {
    v: 1;
    sessionId: string;
    revision: number;
    type: K;
    payload: NativeMapEvents[K];
  };
}[keyof NativeMapEvents];
