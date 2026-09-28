import type { ApiFailure } from "@/shared/api/client";
import type { Direction, ErrorCode, RouteStatus } from "@/shared/api/routeForecast.types";

type FailureKind = Exclude<ApiFailure["kind"], "aborted">;

type SeatLevel = "high" | "low" | "veryLow";

export interface FailureProperties {
  error_kind: FailureKind;
  http_status?: number;
  error_code?: Extract<ErrorCode, "MODEL_OUT_OF_SCOPE">;
}

interface StopExpandedBase {
  route_id: string;
  direction: Direction;
  stop_id: string;
  stop_sequence: number;
  position: number;
  expand_index: number;
}

export interface EventMap {
  route_selected: {
    route_id: string;
    route_status: RouteStatus;
    list_position: number;
    route_count: number;
  };

  route_list_unavailable: FailureProperties & { attempt: number };

  board_viewed: {
    route_id: string;
    direction: Direction;
    model_release_id: string;
    forecast_age_sec: number;
    vehicles_in_service: number;
    boarding_stop_count: number;
    forecast_stop_count: number;
    high_count: number;
    low_count: number;
    very_low_count: number;
    load_ms: number;
  };

  board_unavailable:
    | ({ route_id: string; reason: "error" } & FailureProperties)
    | { route_id: string; reason: "outOfService"; vehicles_in_service: number };

  direction_switched: {
    route_id: string;
    to_direction: Direction;
  };

  stop_expanded: StopExpandedBase &
    (
      | { stop_kind: "boarding"; level: SeatLevel; stops_away: number; arrival_count: number }
      | { stop_kind: "noForecast"; arrival_count: 0 }
    );
}
