export type ErrorCode =
  | "INVALID_ROUTE_ID"
  | "INVALID_REQUEST"
  | "ROUTE_NOT_FOUND"
  | "ENDPOINT_NOT_FOUND"
  | "METHOD_NOT_ALLOWED"
  | "INTERNAL_ERROR"
  | "SERVICE_UNAVAILABLE"
  | "MODEL_OUT_OF_SCOPE"
  | "NO_RECENT_OBSERVATION";

export interface ErrorResponse {
  code: ErrorCode;
  message: string;
  requestId: string;
}

export type Direction = "UP" | "DOWN";

export type RouteStatus = "FORECAST_READY" | "PREPARING";

export interface RouteCore {
  id: string;
  displayName: string;
  startStopName: string;
  endStopName: string;
  status: RouteStatus;
}

export type RouteSummary = RouteCore;

export interface RouteListResponse {
  routes: RouteSummary[];
}

export interface DirectionInfo {
  id: Direction;
  name: string;
  originStopName: string;
  terminalStopName: string;
  firstDepartureTime: string;
  lastDepartureTime: string;
}

export interface BoardRoute extends RouteCore {
  turnSequence: number | null;
  referenceVersionId: string;
  directions: [DirectionInfo, ...DirectionInfo[]];
}

export interface ModelInfo {
  releaseId: string;
  trainedThrough: string;
}

export interface ApproachingVehicle {
  vehicleId: string | null;
  horizonStops: number;
  seatAvailableProbability: number;
  expectedSeats?: number;
}

export interface StopState {
  sequence: number;
  stopId: string;
  name: string;
  direction: Direction;
  boardingAllowed: boolean;
  approachingVehicles: ApproachingVehicle[];
}

export interface Board {
  route: BoardRoute;
  observedAt: string;
  staleAt: string;
  model: ModelInfo;
  vehiclesInService: number;
  stops: StopState[];
}

export interface ExactSeat {
  kind: "EXACT";
  remaining: number;
}

export interface UnknownSeat {
  kind: "UNKNOWN";
  remaining?: never;
}

export type SeatInfo = ExactSeat | UnknownSeat;

export type Phase = "ARRIVING" | "DEPARTED" | "IN_TRANSIT";

export interface Vehicle {
  vehicleId: string | null;
  direction: Direction;
  currentStopSequence: number;
  stopId: string;
  stopName: string;
  phase: Phase;
  seat: SeatInfo;
}

export type ObservationState = "VEHICLES_PRESENT" | "NO_VEHICLES_OBSERVED" | "UNKNOWN";

export interface Observation {
  state: ObservationState;
  observedAt: string | null;
  staleAt: string | null;
}

export interface LiveVehicles {
  routeId: string;
  referenceVersionId: string;
  observation: Observation;
  vehicles: Vehicle[];
}
