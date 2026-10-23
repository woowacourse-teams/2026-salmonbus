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
  eta: FavoriteEta;
}

export interface AvailableFavoriteEta {
  status: "AVAILABLE";
  expectedArrivalAt: string;
  remainingSeconds: number;
  reason?: never;
}

export interface UnavailableFavoriteEta {
  status: "UNAVAILABLE";
  reason: string;
  expectedArrivalAt?: never;
  remainingSeconds?: never;
}

export type FavoriteEta = AvailableFavoriteEta | UnavailableFavoriteEta;

export interface FavoriteRoute {
  id: string;
  displayName?: string;
  terminalStopName?: string;
}

export interface FavoriteStop {
  stopId: string;
  name?: string;
  direction?: Direction;
}

export interface HomeFavorite {
  id: string;
  status: FavoriteStatus;
  route: FavoriteRoute;
  stop: FavoriteStop;
  approachingVehicles: FavoriteArrival[];
}

export interface HomeFavoriteGroup {
  id: string;
  name: string;
  favorites: HomeFavorite[];
}

export interface HomeFavorites {
  groups: HomeFavoriteGroup[];
}
