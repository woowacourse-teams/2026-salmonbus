import type { Direction, ErrorCode, RouteStatus } from "@/shared/api/routeForecast.types";

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

export interface EventMap {
  route_selected: {
    route_id: string;
    route_status: RouteStatus;
    list_position: number;
    route_count: number;
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
}
