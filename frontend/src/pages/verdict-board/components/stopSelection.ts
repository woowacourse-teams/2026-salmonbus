import type { Direction, StopState } from "@/shared/api/routeForecast.types";

export interface StopSelection {
  direction: Direction;
  sequence: StopState["sequence"];
}
