import type { ErrorCode } from "@/shared/api/apiError";
import type { LoginProvider } from "@/shared/api/auth.types";
import type { Direction, RouteStatus } from "@/shared/api/routeForecast.types";

export type FailureProperties =
  | {
      error_kind: "contract";
      http_status: number;
      error_code?: Extract<ErrorCode, "MODEL_OUT_OF_SCOPE">;
    }
  | {
      error_kind: "rateLimited" | "malformed";
      http_status: number;
    }
  | {
      error_kind: "timeout" | "network";
    };

type StopExpandedBase = {
  route_id: string;
  direction: Direction;
  stop_id: string;
  stop_sequence: number;
  row_position: number;
  expand_index: number;
};

export interface EventMap {
  route_selected: {
    route_id: string;
    route_status: RouteStatus;
    result_position: number;
    result_count: number;
  };

  route_search_unmatched: {
    query: string;
  };

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
    unavailable_count: number;
    load_ms: number;
  };

  board_unavailable:
    | ({ route_id: string; reason: "error" } & FailureProperties)
    | { route_id: string; reason: "outOfService"; vehicles_in_service: number };

  direction_switched: {
    route_id: string;
    to_direction: Direction;
  };

  stop_expanded:
    | (StopExpandedBase & {
        stop_kind: "boarding";
        forecast_status: "AVAILABLE";
        level: "high" | "low" | "veryLow";
        stops_away: number;
        arrival_count: number;
      })
    | (StopExpandedBase & {
        stop_kind: "boarding";
        forecast_status: "UNAVAILABLE";
        stops_away: number;
        arrival_count: number;
      })
    | (StopExpandedBase & { stop_kind: "noVehicle"; arrival_count: 0 });

  login_started: {
    provider: LoginProvider;
    entry: "favorite" | "home" | "mypage";
  };

  login_finished: {
    provider: LoginProvider | "unknown";
    result: "succeeded" | "cancelled" | "failed";
  };
}
