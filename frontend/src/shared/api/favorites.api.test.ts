import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { requestJson } from "./client";
import { fetchFavoriteGroups, saveFavoriteSelections, saveGroupOrder } from "./favorites.api";
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
          route: { id: "200000001", displayName: "3330", directionName: "안양역" },
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

describe("즐겨찾기 API 초안 연결", () => {
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
    const missingName = structuredClone(home);
    Reflect.deleteProperty(missingName.groups[0]!.favorites[0]!.route, "directionName");
    expect(isHomeFavorites(missingName)).toBe(false);
    const invalid = structuredClone(home);
    invalid.groups[0]!.favorites[0]!.approachingVehicles[0]!.forecast.seatAvailableProbability = 1.1;
    expect(isHomeFavorites(invalid)).toBe(false);
    expect(isHomeFavorites({ groups: [] })).toBe(true);
  });
});
