export type Direction = "UP" | "DOWN";

export interface DirectionInfo {
  id: Direction;
  name: string;
}

export interface BoardRoute {
  displayName: string;
  directions: DirectionInfo[];
}

export interface EstimatedSeatForecast {
  kind: "ESTIMATED";
  seatAvailableProbability: number;
}

export interface NotApplicableSeatForecast {
  kind: "NOT_APPLICABLE";
}

export type SeatForecast = EstimatedSeatForecast | NotApplicableSeatForecast;

export interface StopState {
  sequence: number;
  name: string;
  direction: Direction;
  seatForecast: SeatForecast;
}

export interface Board {
  route: BoardRoute;
  stops: StopState[];
}
