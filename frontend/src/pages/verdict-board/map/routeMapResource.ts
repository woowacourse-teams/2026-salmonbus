import type { ApiError } from "@/shared/api/client";
import { nextPollFrom } from "@/shared/api/pollSchedule";
import type { RouteGeometry } from "@/shared/api/routeGeometry.types";
import { routeGeometryMapDataFor } from "./routeGeometryMapData";
import type { RouteMapData } from "./routeMapModel";

const RETRY_DELAY_MS = 60_000;

export type RouteMapResource =
  | { status: "loading" | "retrying" | "error" | "conflict"; data: null }
  | { status: "preparing" | "generating" | "withheld"; data: RouteMapData | null }
  | { status: "ready"; data: RouteMapData };

export interface LoadedMapData {
  identity: string;
  revision: string | null;
  resource: RouteMapResource;
  retrying: boolean;
}

export type GeometryFailureDecision =
  { kind: "conflict" } | { kind: "keepReadyMap" } | { kind: "retry"; delayMs: number } | { kind: "stop" };

export function geometryFailureDecisionFor(result: ApiError, hasReadyMap: boolean): GeometryFailureDecision {
  const { failure } = result;
  if (failure.kind === "contract" && failure.status === 409 && failure.error.code === "REFERENCE_VERSION_CONFLICT") {
    return { kind: "conflict" };
  }
  if (hasReadyMap) return { kind: "keepReadyMap" };
  const poll = nextPollFrom(result);
  return failure.kind !== "malformed" && poll.kind === "again"
    ? { kind: "retry", delayMs: Math.max(RETRY_DELAY_MS, poll.delayMs) }
    : { kind: "stop" };
}

/** 조회가 실패해도 같은 판본에서 이전에 검증된 지도 데이터는 계속 사용한다. */
export function failedMapDataFrom(previous: LoadedMapData | null, identity: string, retrying: boolean): LoadedMapData {
  return previous?.identity === identity && (previous.resource.status === "withheld" || previous.resource.data !== null)
    ? { identity, revision: previous.revision, resource: previous.resource, retrying }
    : { identity, revision: null, resource: { status: retrying ? "retrying" : "error", data: null }, retrying };
}

export interface LoadedGeometry {
  revision: string | null;
  resource: RouteMapResource;
  preparing: boolean;
}

export function loadedGeometryFor(
  geometry: RouteGeometry,
  route: { id: string; referenceVersionId: string },
): LoadedGeometry {
  const revision = typeof geometry.geometryRevision === "string" ? geometry.geometryRevision : null;
  const data = routeGeometryMapDataFor(geometry, route);
  const reason = geometry.status === "UNAVAILABLE" ? geometry.reason : null;
  const preparing = reason === "NOT_COLLECTED" || reason === "GENERATION_PENDING";
  const resource: RouteMapResource = preparing
    ? { status: reason === "GENERATION_PENDING" ? "generating" : "preparing", data }
    : reason === "DISPLAY_WITHHELD"
      ? { status: "withheld", data }
      : data === null
        ? { status: "error", data: null }
        : { status: "ready", data };
  return { revision, resource, preparing };
}

/** 개정과 상태가 그대로면 이전 지도 데이터 객체를 유지해 지도를 다시 만들지 않는다. */
export function loadedMapDataFrom(
  previous: LoadedMapData | null,
  identity: string,
  { revision, resource }: Pick<LoadedGeometry, "revision" | "resource">,
): LoadedMapData {
  return revision !== null &&
    previous?.identity === identity &&
    previous.revision === revision &&
    previous.resource.status === resource.status
    ? { identity, revision, resource: previous.resource, retrying: false }
    : { identity, revision, resource, retrying: false };
}
