import { describe, expect, it } from "@jest/globals";
import type { ApiError, ApiFailure } from "@/shared/api/client";
import type { ErrorCode } from "@/shared/api/apiError";
import type { RouteGeometry } from "@/shared/api/routeGeometry.types";
import {
  failedMapDataFrom,
  geometryFailureDecisionFor,
  loadedGeometryFor,
  loadedMapDataFrom,
  type LoadedMapData,
} from "./routeMapResource";

const route = { id: "200000001", referenceVersionId: "12" };
const identity = JSON.stringify([route.id, route.referenceVersionId]);

function failed(failure: ApiFailure): ApiError {
  return { ok: false, failure };
}

function contract(status: number, code: ErrorCode, retryAfterMs: number | null = null): ApiError {
  return failed({ kind: "contract", status, retryAfterMs, error: { code, message: "", requestId: "geometry" } });
}

function geometryWith(overrides: Partial<RouteGeometry> = {}): RouteGeometry {
  return {
    routeId: route.id,
    referenceVersionId: route.referenceVersionId,
    geometryRevision: "geometry-1",
    name: "8100",
    startPoint: "기점",
    endPoint: "회차점",
    status: "READY",
    reason: null,
    coordinateSystem: "WGS84_ASSUMED",
    turningPoint: { stopSequence: 2 },
    busStops: [
      {
        stationId: "first",
        stopSequence: 1,
        name: "기점",
        direction: "UP",
        boardingAllowed: true,
        point: { x: 127, y: 37 },
      },
      {
        stationId: "turn",
        stopSequence: 2,
        name: "회차점",
        direction: "UP",
        boardingAllowed: true,
        point: { x: 127.1, y: 37.1 },
      },
    ],
    points: [
      { x: 127, y: 37 },
      { x: 127.1, y: 37.1 },
    ],
    paths: null,
    boundary: { left: 127, top: 37.1, right: 127.1, bottom: 37 },
    ...overrides,
  };
}

function readyMapData(): LoadedMapData {
  return loadedMapDataFrom(null, identity, loadedGeometryFor(geometryWith(), route));
}

describe("geometryFailureDecisionFor", () => {
  it("판본 충돌이면 보여 주던 지도가 있어도 충돌로 본다", () => {
    const conflict = contract(409, "REFERENCE_VERSION_CONFLICT");

    expect(geometryFailureDecisionFor(conflict, false)).toEqual({ kind: "conflict" });
    expect(geometryFailureDecisionFor(conflict, true)).toEqual({ kind: "conflict" });
  });

  it("보여 주던 지도가 있으면 다시 확인이 실패해도 그대로 두고 다시 시도하지 않는다", () => {
    expect(geometryFailureDecisionFor(contract(503, "INTERNAL_ERROR"), true)).toEqual({ kind: "keepReadyMap" });
    expect(geometryFailureDecisionFor(failed({ kind: "network", cause: null }), true)).toEqual({
      kind: "keepReadyMap",
    });
  });

  it("503이면 1분 뒤 다시 시도하고, 서버가 더 긴 대기를 알리면 그 시간을 따른다", () => {
    expect(geometryFailureDecisionFor(contract(503, "INTERNAL_ERROR"), false)).toEqual({
      kind: "retry",
      delayMs: 60_000,
    });
    expect(geometryFailureDecisionFor(contract(503, "INTERNAL_ERROR", 5_000), false)).toEqual({
      kind: "retry",
      delayMs: 60_000,
    });
    expect(geometryFailureDecisionFor(contract(503, "INTERNAL_ERROR", 90_000), false)).toEqual({
      kind: "retry",
      delayMs: 90_000,
    });
  });

  it("연결 실패와 시간 초과도 다시 시도한다", () => {
    expect(geometryFailureDecisionFor(failed({ kind: "network", cause: null }), false).kind).toBe("retry");
    expect(geometryFailureDecisionFor(failed({ kind: "timeout" }), false).kind).toBe("retry");
  });

  it("404 같은 4xx 응답이면 다시 시도하지 않는다", () => {
    expect(geometryFailureDecisionFor(contract(404, "ROUTE_NOT_FOUND"), false)).toEqual({ kind: "stop" });
  });

  it("HTML 오류 페이지처럼 형식이 잘못된 응답이면 다시 시도하지 않는다", () => {
    expect(geometryFailureDecisionFor(failed({ kind: "malformed", status: 502, requestId: null }), false)).toEqual({
      kind: "stop",
    });
  });
});

describe("failedMapDataFrom", () => {
  it("보여 준 지도가 없으면 다시 시도하는 중인지에 따라 재시도 중이나 오류로 둔다", () => {
    expect(failedMapDataFrom(null, identity, true)).toEqual({
      identity,
      revision: null,
      resource: { status: "retrying", data: null },
      retrying: true,
    });
    expect(failedMapDataFrom(null, identity, false)).toEqual({
      identity,
      revision: null,
      resource: { status: "error", data: null },
      retrying: false,
    });
  });

  it("같은 판본에서 검증된 지도 데이터는 실패 뒤에도 그대로 쓴다", () => {
    const preparing = loadedMapDataFrom(
      null,
      identity,
      loadedGeometryFor(geometryWith({ status: "UNAVAILABLE", reason: "GENERATION_PENDING" }), route),
    );

    const stopped = failedMapDataFrom(preparing, identity, false);

    expect(stopped.resource).toBe(preparing.resource);
    expect(stopped.revision).toBe("geometry-1");
    expect(stopped.retrying).toBe(false);
    expect(failedMapDataFrom(preparing, identity, true).retrying).toBe(true);
  });

  it("표시 보류는 지도 데이터가 없어도 실패 뒤에 유지한다", () => {
    const withheld: LoadedMapData = {
      identity,
      revision: "geometry-1",
      resource: { status: "withheld", data: null },
      retrying: false,
    };

    expect(failedMapDataFrom(withheld, identity, false).resource).toBe(withheld.resource);
  });

  it("다른 판본의 지도 데이터는 이어 쓰지 않는다", () => {
    const otherIdentity = JSON.stringify([route.id, "13"]);

    expect(failedMapDataFrom(readyMapData(), otherIdentity, false)).toEqual({
      identity: otherIdentity,
      revision: null,
      resource: { status: "error", data: null },
      retrying: false,
    });
  });
});

describe("loadedGeometryFor", () => {
  it("도로 형상이 있으면 표시할 수 있는 지도다", () => {
    const loaded = loadedGeometryFor(geometryWith(), route);

    expect(loaded.resource.status).toBe("ready");
    expect(loaded.resource.data?.stops).toHaveLength(2);
    expect(loaded).toMatchObject({ revision: "geometry-1", preparing: false });
  });

  it.each([
    ["NOT_COLLECTED", "preparing"],
    ["GENERATION_PENDING", "generating"],
  ] as const)("%s이면 %s 상태로 두고 다시 확인한다", (reason, status) => {
    const loaded = loadedGeometryFor(geometryWith({ status: "UNAVAILABLE", reason }), route);

    expect(loaded.resource.status).toBe(status);
    expect(loaded.preparing).toBe(true);
  });

  it("표시 보류면 보류 상태이고 다시 확인하지 않는다", () => {
    const loaded = loadedGeometryFor(geometryWith({ status: "UNAVAILABLE", reason: "DISPLAY_WITHHELD" }), route);

    expect(loaded.resource.status).toBe("withheld");
    expect(loaded.preparing).toBe(false);
  });

  it("보드와 판본이 다른 응답은 지도 데이터를 만들지 못해 오류다", () => {
    const loaded = loadedGeometryFor(geometryWith({ referenceVersionId: "different-reference" }), route);

    expect(loaded.resource).toEqual({ status: "error", data: null });
    expect(loaded.preparing).toBe(false);
  });

  it("개정 값이 문자열이 아니면 개정을 모르는 것으로 둔다", () => {
    const geometry = { ...geometryWith(), geometryRevision: undefined } as unknown as RouteGeometry;

    expect(loadedGeometryFor(geometry, route).revision).toBeNull();
  });
});

describe("loadedMapDataFrom", () => {
  it("개정과 상태가 그대로면 이전 지도 데이터 객체를 유지한다", () => {
    const previous = readyMapData();

    const next = loadedMapDataFrom(previous, identity, loadedGeometryFor(geometryWith(), route));

    expect(next.resource).toBe(previous.resource);
    expect(next.retrying).toBe(false);
  });

  it("개정이 바뀌면 새 지도 데이터로 바꾼다", () => {
    const previous = readyMapData();
    const revised = loadedGeometryFor(geometryWith({ geometryRevision: "geometry-2" }), route);

    const next = loadedMapDataFrom(previous, identity, revised);

    expect(next.resource).toBe(revised.resource);
    expect(next.revision).toBe("geometry-2");
  });

  it("개정이 같아도 상태가 바뀌면 새 상태로 바꾼다", () => {
    const generating = loadedMapDataFrom(
      null,
      identity,
      loadedGeometryFor(geometryWith({ status: "UNAVAILABLE", reason: "GENERATION_PENDING" }), route),
    );
    const ready = loadedGeometryFor(geometryWith(), route);

    expect(loadedMapDataFrom(generating, identity, ready).resource).toBe(ready.resource);
  });

  it("개정을 모르면 상태가 같아도 새 지도 데이터로 바꾼다", () => {
    const first = { ...loadedGeometryFor(geometryWith(), route), revision: null };
    const second = { ...loadedGeometryFor(geometryWith(), route), revision: null };
    const previous = loadedMapDataFrom(null, identity, first);

    expect(loadedMapDataFrom(previous, identity, second).resource).toBe(second.resource);
  });

  it("실패 뒤 다시 성공하면 재시도 표시를 끈다", () => {
    const retrying = failedMapDataFrom(null, identity, true);

    expect(loadedMapDataFrom(retrying, identity, loadedGeometryFor(geometryWith(), route)).retrying).toBe(false);
  });
});
