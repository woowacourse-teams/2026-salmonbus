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
  unmount: Record<never, never>;
}
export interface NativeMapEvents {
  ready: Record<never, never>;
  ack: { appliedRevision: number };
  stopPress: { selection: NativeStopSelection };
  vehiclePress: { selection: NativeStopSelection; key: string };
  mapPress: Record<never, never>;
  locationResult: {
    requestId: number;
    coordinate?: NativeCoordinate;
    error?: "denied" | "timeout" | "unavailable";
  };
  error: { message: string };
}
/** 지도 메시지마다 payload에 함께 싣는 값. 앱은 지금 지도 세션의 revision이 늘어난 메시지만 적용한다. */
export interface NativeMapSession {
  sessionId: string;
  revision: number;
}
export type NativeMapBridgeCommands = {
  [K in keyof NativeMapCommands as `map.${K}`]: NativeMapSession & NativeMapCommands[K];
};
export type NativeMapBridgeEvents = {
  [K in keyof NativeMapEvents as `map.${K}`]: NativeMapSession & NativeMapEvents[K];
};
export type NativeMapEvent = {
  [K in keyof NativeMapEvents]: { type: K; payload: NativeMapEvents[K] };
}[keyof NativeMapEvents];
