import type {
  FavoriteArrival,
  FavoriteGroup,
  FavoriteGroups,
  HomeFavorite,
  HomeFavorites,
  SavedFavorite,
} from "./favorites.types";

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function uuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function identifier(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,40}$/.test(value);
}

function direction(value: unknown) {
  return value === "UP" || value === "DOWN";
}

function isSavedFavorite(value: unknown): value is SavedFavorite {
  return (
    record(value) &&
    uuid(value.id) &&
    identifier(value.routeId) &&
    identifier(value.stopId) &&
    direction(value.direction)
  );
}

export function isGroupName(value: unknown): value is { id: string; name: string } {
  return record(value) && uuid(value.id) && typeof value.name === "string" && value.name.trim().length > 0;
}

export function isFavoriteGroup(value: unknown): value is FavoriteGroup {
  return (
    isGroupName(value) &&
    "favorites" in value &&
    Array.isArray(value.favorites) &&
    value.favorites.length <= 2 &&
    value.favorites.every(isSavedFavorite)
  );
}

export function isFavoriteGroups(value: unknown): value is FavoriteGroups {
  return (
    record(value) && Array.isArray(value.groups) && value.groups.length <= 3 && value.groups.every(isFavoriteGroup)
  );
}

function isArrival(value: unknown): value is FavoriteArrival {
  if (
    !record(value) ||
    !(value.vehicleId === null || typeof value.vehicleId === "string") ||
    typeof value.horizonStops !== "number" ||
    !Number.isInteger(value.horizonStops) ||
    value.horizonStops < 0
  )
    return false;
  const { forecast, eta } = value;
  if (!record(forecast) || !record(eta)) return false;
  const validForecast =
    forecast.status === "UNAVAILABLE" ||
    (forecast.status === "AVAILABLE" &&
      typeof forecast.seatAvailableProbability === "number" &&
      Number.isFinite(forecast.seatAvailableProbability) &&
      forecast.seatAvailableProbability >= 0 &&
      forecast.seatAvailableProbability <= 1 &&
      (forecast.expectedSeats === undefined ||
        (typeof forecast.expectedSeats === "number" &&
          Number.isFinite(forecast.expectedSeats) &&
          forecast.expectedSeats >= 0)));
  const validEta =
    eta.status === "UNAVAILABLE"
      ? typeof eta.reason === "string"
      : eta.status === "AVAILABLE" &&
        typeof eta.expectedArrivalAt === "string" &&
        Number.isFinite(Date.parse(eta.expectedArrivalAt)) &&
        typeof eta.remainingSeconds === "number" &&
        Number.isFinite(eta.remainingSeconds) &&
        eta.remainingSeconds >= 0;
  return validForecast && validEta;
}

function isHomeFavorite(value: unknown): value is HomeFavorite {
  if (
    !record(value) ||
    !uuid(value.id) ||
    typeof value.status !== "string" ||
    !["READY", "NO_VEHICLE", "PREPARING", "UNAVAILABLE", "AMBIGUOUS", "ERROR"].includes(value.status)
  )
    return false;
  return (
    record(value.route) &&
    identifier(value.route.id) &&
    typeof value.route.displayName === "string" &&
    typeof value.route.directionName === "string" &&
    value.route.directionName.trim().length > 0 &&
    record(value.stop) &&
    identifier(value.stop.stopId) &&
    typeof value.stop.name === "string" &&
    direction(value.stop.direction) &&
    Array.isArray(value.approachingVehicles) &&
    value.approachingVehicles.length <= 3 &&
    value.approachingVehicles.every(isArrival)
  );
}

export function isHomeFavorites(value: unknown): value is HomeFavorites {
  return (
    record(value) &&
    Array.isArray(value.groups) &&
    value.groups.length <= 3 &&
    value.groups.every(
      (group) =>
        isGroupName(group) &&
        "favorites" in group &&
        Array.isArray(group.favorites) &&
        group.favorites.length <= 2 &&
        group.favorites.every(isHomeFavorite),
    )
  );
}
