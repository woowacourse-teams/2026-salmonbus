import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { requestJson } from "./client";
import { fetchFavoriteGroups, fetchHomeFavorites, saveFavoriteSelections, saveGroupOrder } from "./favorites.api";
import { isHomeFavorites } from "./favorites.schema";

const originalFetch = global.fetch;
const id = "10000000-0000-4000-8000-000000000001";
const home = {
  groups: [
    {
      id,
      name: "즐겨찾기",
      favorites: [
        {
          id,
          status: "READY",
          route: { id: "200000001", displayName: "3330", terminalStopName: "안양역" },
          stop: { stopId: "200000011", name: "판교역", direction: "UP" },
          approachingVehicles: [
            {
              vehicleId: null,
              horizonStops: 3,
              forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 7.4 },
              eta: { status: "UNAVAILABLE", reason: "SOURCE_NOT_PROVIDED" },
            },
          ],
        },
      ],
    },
  ],
};

beforeEach(() => {
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { cookie: "__Host-XSRF-TOKEN=csrf-test" },
  });
});
afterEach(() => {
  global.fetch = originalFetch;
  Reflect.deleteProperty(globalThis, "document");
});

describe("즐겨찾기 API 연결", () => {
  it("배포된 terminalStopName 응답을 홈 조회의 성공 결과로 읽는다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(home)));
    expect(await fetchHomeFavorites(id, new AbortController().signal)).toMatchObject({ ok: true, body: home });
  });
  it("PUT 저장에 전체 선택 목록과 방면, JSON과 CSRF를 보낸다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    expect(
      await saveFavoriteSelections({ routeId: "200000001", stopId: "200000011", direction: "DOWN" }, []),
    ).toMatchObject({ ok: true });
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/me/favorite-selections",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ routeId: "200000001", stopId: "200000011", direction: "DOWN", favoriteGroupIds: [] }),
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }),
      }),
    );
  });
  it("초안의 requestId 없는 오류를 읽되 기존 API의 검증은 유지한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockImplementation(
        async () =>
          new Response(JSON.stringify({ code: "LAST_FAVORITE_GROUP", message: "마지막 그룹" }), { status: 409 }),
      );
    expect(await saveGroupOrder([id])).toMatchObject({
      ok: false,
      failure: { kind: "contract", error: { code: "LAST_FAVORITE_GROUP" } },
    });
    expect(await requestJson("/api/me")).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
  it("깨진 그룹 성공 응답을 회원 데이터로 받아들이지 않는다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({ groups: [{ id: "invalid", name: "출근", favorites: [] }] })));
    expect(await fetchFavoriteGroups()).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
  it("ETA가 없는 차량의 좌석 예보는 유효하며 잘못된 확률과 ETA는 거절한다", () => {
    expect(isHomeFavorites(home)).toBe(true);
    const invalid = structuredClone(home);
    invalid.groups[0]!.favorites[0]!.approachingVehicles[0]!.forecast.seatAvailableProbability = 1.1;
    expect(isHomeFavorites(invalid)).toBe(false);
    expect(isHomeFavorites({ groups: [] })).toBe(true);
  });
  it("노선과 정류장의 ID만 남은 조회 불가 항목도 다른 정상 항목과 함께 읽는다", () => {
    const unavailable = {
      id: "10000000-0000-4000-8000-000000000002",
      status: "UNAVAILABLE",
      reason: "OBSERVATION_UNAVAILABLE",
      route: { id: "200000001" },
      stop: { stopId: "200000011" },
      approachingVehicles: [],
    };
    expect(
      isHomeFavorites({ groups: [{ ...home.groups[0], favorites: [home.groups[0]!.favorites[0], unavailable] }] }),
    ).toBe(true);
  });
  it.each([
    ["route", "displayName"],
    ["route", "terminalStopName"],
    ["stop", "name"],
    ["stop", "direction"],
  ] as const)("%s.%s는 생략을 허용하지만 잘못된 타입은 거절한다", (object, field) => {
    const response = structuredClone(home);
    const value = response.groups[0]!.favorites[0]![object];
    Reflect.deleteProperty(value, field);
    expect(isHomeFavorites(response)).toBe(true);
    for (const invalid of [null, 42, {}, []]) {
      Reflect.set(value, field, invalid);
      expect(isHomeFavorites(response)).toBe(false);
    }
    if (field === "direction") {
      Reflect.set(value, field, "UNKNOWN");
      expect(isHomeFavorites(response)).toBe(false);
    }
  });
});
