import type { Direction, VehicleForecast } from "./routeForecast.types";

export interface FavoriteTarget {
  routeId: string;
  stopId: string;
  direction: Direction;
}

export interface SavedFavorite extends FavoriteTarget {
  id: string;
}

export interface FavoriteGroup {
  id: string;
  name: string;
  favorites: SavedFavorite[];
}

export interface FavoriteGroups {
  groups: FavoriteGroup[];
}

export type FavoriteStatus = "READY" | "NO_VEHICLE" | "PREPARING" | "UNAVAILABLE" | "AMBIGUOUS" | "ERROR";

export interface FavoriteArrival {
  vehicleId: string | null;
  horizonStops: number;
  forecast: VehicleForecast;
  eta:
    | { status: "AVAILABLE"; expectedArrivalAt: string; remainingSeconds: number }
    | { status: "UNAVAILABLE"; reason: string };
}

export interface HomeFavorite {
  id: string;
  status: FavoriteStatus;
  route: { id: string; displayName: string; directionName: string };
  stop: { stopId: string; name: string; direction: Direction };
  approachingVehicles: FavoriteArrival[];
}

export interface HomeFavorites {
  groups: { id: string; name: string; favorites: HomeFavorite[] }[];
}

export type FavoriteErrorCode =
  | "INVALID_FAVORITE_GROUP_NAME"
  | "INVALID_ORDER"
  | "FAVORITE_GROUP_NOT_FOUND"
  | "FAVORITE_NOT_FOUND"
  | "FAVORITE_GROUP_LIMIT_EXCEEDED"
  | "FAVORITE_GROUP_NAME_CONFLICT"
  | "LAST_FAVORITE_GROUP"
  | "FAVORITE_LIMIT_EXCEEDED"
  | "FAVORITE_TARGET_AMBIGUOUS"
  | "FAVORITE_TARGET_UNAVAILABLE";
