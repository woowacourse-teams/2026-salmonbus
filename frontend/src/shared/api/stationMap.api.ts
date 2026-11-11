import { isRecord } from "@/shared/utils/valueGuards";
import { requestJson, type ApiResult, type ApiSuccess } from "./client";
import type { Direction } from "./routeForecast.types";
import type { ArrivalEta, StationArrivalStatus } from "./stationArrivals.types";

interface MapStation {
  stationId: string;
  name: string;
  point: { x: number; y: number };
}

export interface StationTileResult {
  status: "READY" | "PENDING";
  stations: MapStation[];
}

interface StationVehicle {
  positionSource: "BOARD" | "ARRIVAL" | null;
  remainingStops: number | null;
  eta: (Pick<ArrivalEta, "status" | "remainingSeconds"> & { expectedArrivalAt: string | null }) | null;
  probability: number | null;
}

interface StationRoute {
  routeId: string;
  name: string | null;
  routeTypeCode: number | null;
  direction: Direction | null;
  arrivals: StationVehicle[];
}

interface StationDetail {
  stationId: string;
  generatedAt: string;
  status: StationArrivalStatus;
  reason: string | null;
  snapshot: { staleAt: string } | null;
  refresh: { status: string; reason: string | null; suggestedRefreshAt: string };
  routes: StationRoute[];
}

function malformed(result: ApiSuccess<unknown>) {
  return { ok: false, failure: { kind: "malformed", status: result.status, requestId: result.requestId } } as const;
}

function isMapStation(value: unknown): value is MapStation {
  return (
    isRecord(value) &&
    typeof value.stationId === "string" &&
    value.stationId.length > 0 &&
    typeof value.name === "string" &&
    value.name.length > 0 &&
    isRecord(value.point) &&
    typeof value.point.x === "number" &&
    Math.abs(value.point.x) <= 180 &&
    typeof value.point.y === "number" &&
    Math.abs(value.point.y) <= 90
  );
}

export async function fetchStationTile(tile: string, signal: AbortSignal): Promise<ApiResult<StationTileResult>> {
  const endpoint = "/api/v2/stations/tiles/14/:x/:y";
  const result = await requestJson<unknown>(`/api/v2/stations/tiles/14/${tile}`, {
    signal,
    errorContext: { endpoint, tags: {} },
  });
  if (!result.ok) return result;
  const { body } = result;
  if (
    !isRecord(body) ||
    !isRecord(body.tile) ||
    body.tile.zoom !== 14 ||
    `${body.tile.x}/${body.tile.y}` !== tile ||
    body.coordinateSystem !== "WGS84" ||
    (body.status !== "READY" && body.status !== "PENDING") ||
    !Array.isArray(body.stations) ||
    !body.stations.every(isMapStation)
  )
    return malformed(result);
  return { ...result, body: { status: body.status, stations: body.stations } };
}

function vehicleFrom(value: unknown, supported: boolean): StationVehicle[] {
  if (!isRecord(value)) return [];
  const eta = value.eta;
  const forecast = value.forecast;
  const positionSource =
    value.positionSource === "BOARD" || value.positionSource === "ARRIVAL" ? value.positionSource : null;
  const probability =
    supported && positionSource === "BOARD" && isRecord(forecast) && forecast.status === "AVAILABLE"
      ? forecast.seatAvailableProbability
      : null;
  return [
    {
      positionSource,
      remainingStops:
        typeof value.remainingStops === "number" && Number.isInteger(value.remainingStops) && value.remainingStops >= 0
          ? value.remainingStops
          : null,
      eta:
        isRecord(eta) &&
        eta.status === "AVAILABLE" &&
        typeof eta.expectedArrivalAt === "string" &&
        typeof eta.remainingSeconds === "number"
          ? { status: "AVAILABLE", expectedArrivalAt: eta.expectedArrivalAt, remainingSeconds: eta.remainingSeconds }
          : null,
      probability: typeof probability === "number" && probability >= 0 && probability <= 1 ? probability : null,
    },
  ];
}

function routeFrom(value: unknown): StationRoute[] {
  if (!isRecord(value) || typeof value.routeId !== "string" || !Array.isArray(value.arrivals)) return [];
  const name = typeof value.name === "string" ? value.name.trim() : "";
  const supported = value.supported === true;
  const service = value.service;
  const direction =
    supported &&
    isRecord(service) &&
    service.matchStatus === "MATCHED" &&
    (service.direction === "UP" || service.direction === "DOWN")
      ? service.direction
      : null;
  return [
    {
      routeId: value.routeId,
      name: name || null,
      routeTypeCode:
        typeof value.routeTypeCode === "number" && Number.isInteger(value.routeTypeCode) ? value.routeTypeCode : null,
      direction,
      arrivals: value.arrivals.flatMap((vehicle) => vehicleFrom(vehicle, supported)),
    },
  ];
}

export async function fetchStationDetail(stationId: string, signal: AbortSignal): Promise<ApiResult<StationDetail>> {
  const endpoint = "/api/v2/stations/:stationId";
  const result = await requestJson<unknown>(endpoint.replace(":stationId", encodeURIComponent(stationId)), {
    signal,
    errorContext: { endpoint, tags: { station_id: stationId } },
  });
  if (!result.ok) return result;
  const { body } = result;
  if (
    !isRecord(body) ||
    body.stationId !== stationId ||
    typeof body.generatedAt !== "string" ||
    (body.status !== "READY" && body.status !== "UNAVAILABLE" && body.status !== "NO_PREDICTION") ||
    !isRecord(body.refresh) ||
    typeof body.refresh.status !== "string" ||
    typeof body.refresh.suggestedRefreshAt !== "string" ||
    !Array.isArray(body.routes)
  )
    return malformed(result);
  const snapshot =
    isRecord(body.snapshot) && typeof body.snapshot.staleAt === "string" ? { staleAt: body.snapshot.staleAt } : null;
  return {
    ...result,
    body: {
      stationId,
      generatedAt: body.generatedAt,
      status: body.status,
      reason: typeof body.reason === "string" ? body.reason : null,
      snapshot,
      refresh: {
        status: body.refresh.status,
        reason: typeof body.refresh.reason === "string" ? body.refresh.reason : null,
        suggestedRefreshAt: body.refresh.suggestedRefreshAt,
      },
      routes: body.routes.flatMap(routeFrom),
    },
  };
}
