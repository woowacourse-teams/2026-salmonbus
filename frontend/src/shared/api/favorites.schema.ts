import { MAX_FAVORITE_GROUPS } from "@/shared/utils/favoritePolicy";
import { isRecord } from "@/shared/utils/valueGuards";
import type { Direction, VehicleForecast } from "./routeForecast.types";
import type {
  FavoriteArrival,
  FavoriteGroup,
  FavoriteGroups,
  FavoriteStatus,
  HomeFavorite,
  HomeFavorites,
  SavedFavorite,
} from "./favorites.types";

const MAX_RECEIVED_FAVORITES_PER_GROUP = 3;

const FAVORITE_STATUSES: { [Status in FavoriteStatus]: Status } = {
  READY: "READY",
  NO_VEHICLE: "NO_VEHICLE",
  PREPARING: "PREPARING",
  UNAVAILABLE: "UNAVAILABLE",
  AMBIGUOUS: "AMBIGUOUS",
  ERROR: "ERROR",
};

function uuidFrom(value: unknown): string | null {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
    ? value
    : null;
}

function identifierFrom(value: unknown): string | null {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,40}$/.test(value) ? value : null;
}

function directionFrom(value: unknown): Direction | null {
  return value === "UP" || value === "DOWN" ? value : null;
}

function listFrom<T>(value: unknown, maximum: number, parse: (value: unknown) => T | null): T[] | null {
  if (!Array.isArray(value) || value.length > maximum) return null;
  const result: T[] = [];
  for (const raw of value) {
    const item = parse(raw);
    if (item === null) return null;
    result.push(item);
  }
  return result;
}

function savedFavoriteFrom(value: unknown): SavedFavorite | null {
  if (!isRecord(value)) return null;
  const id = uuidFrom(value.id);
  const routeId = identifierFrom(value.routeId);
  const stopId = identifierFrom(value.stopId);
  const direction = directionFrom(value.direction);
  return id === null || routeId === null || stopId === null || direction === null
    ? null
    : { id, routeId, stopId, direction };
}

export function groupNameFrom(value: unknown): Pick<FavoriteGroup, "id" | "name"> | null {
  if (!isRecord(value)) return null;
  const id = uuidFrom(value.id);
  return id === null || typeof value.name !== "string" || value.name.trim().length === 0
    ? null
    : { id, name: value.name };
}

export function favoriteGroupFrom(value: unknown): FavoriteGroup | null {
  if (!isRecord(value)) return null;
  const group = groupNameFrom(value);
  const favorites = listFrom(value.favorites, MAX_RECEIVED_FAVORITES_PER_GROUP, savedFavoriteFrom);
  if (group === null || favorites === null || new Set(favorites.map((item) => item.id)).size !== favorites.length)
    return null;
  return { id: group.id, name: group.name, favorites };
}

export function favoriteGroupsFrom(value: unknown): FavoriteGroups | null {
  if (!isRecord(value)) return null;
  const groups = listFrom(value.groups, MAX_FAVORITE_GROUPS, favoriteGroupFrom);
  if (groups === null || new Set(groups.map((group) => group.id)).size !== groups.length) return null;
  return { groups };
}

function forecastFrom(value: unknown): VehicleForecast | null {
  if (!isRecord(value)) return null;
  if (value.status === "UNAVAILABLE") return { status: "UNAVAILABLE" };
  if (
    value.status !== "AVAILABLE" ||
    typeof value.seatAvailableProbability !== "number" ||
    !Number.isFinite(value.seatAvailableProbability) ||
    value.seatAvailableProbability < 0 ||
    value.seatAvailableProbability > 1
  )
    return null;
  const forecast: VehicleForecast = { status: "AVAILABLE", seatAvailableProbability: value.seatAvailableProbability };
  if (value.expectedSeats !== undefined) {
    if (typeof value.expectedSeats !== "number" || !Number.isFinite(value.expectedSeats) || value.expectedSeats < 0)
      return null;
    forecast.expectedSeats = value.expectedSeats;
  }
  return forecast;
}

function etaFrom(value: unknown): FavoriteArrival["eta"] | null {
  if (!isRecord(value)) return null;
  if (value.status === "UNAVAILABLE")
    return typeof value.reason === "string" ? { status: "UNAVAILABLE", reason: value.reason } : null;
  if (
    value.status !== "AVAILABLE" ||
    typeof value.expectedArrivalAt !== "string" ||
    !Number.isFinite(Date.parse(value.expectedArrivalAt)) ||
    typeof value.remainingSeconds !== "number" ||
    !Number.isFinite(value.remainingSeconds) ||
    value.remainingSeconds < 0
  )
    return null;
  return { status: "AVAILABLE", expectedArrivalAt: value.expectedArrivalAt, remainingSeconds: value.remainingSeconds };
}

function arrivalFrom(value: unknown): FavoriteArrival | null {
  if (
    !isRecord(value) ||
    !(value.vehicleId === null || typeof value.vehicleId === "string") ||
    typeof value.remainingStops !== "number" ||
    !Number.isInteger(value.remainingStops) ||
    value.remainingStops < 0
  )
    return null;
  const forecast = forecastFrom(value.forecast);
  const eta = etaFrom(value.eta);
  return forecast === null || eta === null
    ? null
    : {
        vehicleId: value.vehicleId,
        remainingStops: value.remainingStops,
        forecast,
        eta,
      };
}

function routeFrom(value: unknown): HomeFavorite["route"] | null {
  if (!isRecord(value)) return null;
  const id = identifierFrom(value.id);
  if (id === null) return null;
  const route: HomeFavorite["route"] = { id };
  if (value.displayName !== undefined) {
    if (typeof value.displayName !== "string") return null;
    route.displayName = value.displayName;
  }
  if (value.terminalStopName !== undefined) {
    if (typeof value.terminalStopName !== "string") return null;
    route.terminalStopName = value.terminalStopName;
  }
  return route;
}

function stopFrom(value: unknown): HomeFavorite["stop"] | null {
  if (!isRecord(value)) return null;
  const stopId = identifierFrom(value.stopId);
  if (stopId === null) return null;
  const stop: HomeFavorite["stop"] = { stopId };
  if (value.name !== undefined) {
    if (typeof value.name !== "string") return null;
    stop.name = value.name;
  }
  if (value.direction !== undefined) {
    const direction = directionFrom(value.direction);
    if (direction === null) return null;
    stop.direction = direction;
  }
  return stop;
}

function homeFavoriteFrom(value: unknown): HomeFavorite | null {
  if (!isRecord(value)) return null;
  const id = uuidFrom(value.id);
  const status = Object.values(FAVORITE_STATUSES).find((status) => status === value.status);
  const route = routeFrom(value.route);
  const stop = stopFrom(value.stop);
  const approachingVehicles = listFrom(value.approachingVehicles, 3, arrivalFrom);
  if (id === null || status === undefined || route === null || stop === null || approachingVehicles === null)
    return null;
  return { id, status, route, stop, approachingVehicles };
}

function homeGroupFrom(value: unknown): HomeFavorites["groups"][number] | null {
  if (!isRecord(value)) return null;
  const group = groupNameFrom(value);
  const favorites = listFrom(value.favorites, MAX_RECEIVED_FAVORITES_PER_GROUP, homeFavoriteFrom);
  if (group === null || favorites === null || new Set(favorites.map((item) => item.id)).size !== favorites.length)
    return null;
  return { id: group.id, name: group.name, favorites };
}

export function homeFavoritesFrom(value: unknown): HomeFavorites | null {
  if (!isRecord(value)) return null;
  const groups = listFrom(value.groups, MAX_FAVORITE_GROUPS, homeGroupFrom);
  if (groups === null || new Set(groups.map((group) => group.id)).size !== groups.length) return null;
  return { groups };
}

export function emptyFrom(value: unknown): void | null {
  return value === undefined ? undefined : null;
}
