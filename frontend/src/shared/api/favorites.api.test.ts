import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { requestJson } from "./client";
import { fetchFavoriteGroups, fetchHomeFavorites, saveFavoriteSelections, saveGroupOrder } from "./favorites.api";
import { favoriteGroupsFrom, homeFavoritesFrom } from "./favorites.schema";
import type { HomeFavorites } from "./favorites.types";

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
              remainingStops: 3,
              forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 7.4 },
              eta: { status: "UNAVAILABLE", reason: "SOURCE_NOT_PROVIDED" },
            },
          ],
        },
      ],
    },
  ],
} satisfies HomeFavorites;

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
  it("검증한 필드로 새 객체를 만들고 서버의 초과 필드는 버린다", () => {
    const parsed = homeFavoritesFrom(home);
    expect(parsed).toEqual(home);
    expect(parsed).not.toBe(home);
    expect(parsed?.groups[0]).not.toBe(home.groups[0]);
    expect(parsed?.groups[0]?.favorites[0]?.route).not.toBe(home.groups[0]!.favorites[0]!.route);
    expect(homeFavoritesFrom({ ...home, internal: "ignore" })).toEqual(home);
    const unavailable = structuredClone(home);
    Reflect.set(unavailable.groups[0]!.favorites[0]!.approachingVehicles[0]!, "forecast", {
      status: "UNAVAILABLE",
      seatAvailableProbability: 0.9,
      expectedSeats: 10,
    });
    expect(homeFavoritesFrom(unavailable)?.groups[0]?.favorites[0]?.approachingVehicles[0]?.forecast).toEqual({
      status: "UNAVAILABLE",
    });
  });
  it("모르는 상태·잘못된 예측은 정상 값으로 바꾸지 않고 거절하며 0은 보존한다", () => {
    for (const status of ["NEW_STATUS", null, 0]) {
      const invalid = structuredClone(home);
      Reflect.set(invalid.groups[0]!.favorites[0]!, "status", status);
      expect(homeFavoritesFrom(invalid)).toBeNull();
    }
    for (const value of [null, undefined, "0", -1, 1.1, NaN, Infinity]) {
      const invalid = structuredClone(home);
      Reflect.set(invalid.groups[0]!.favorites[0]!.approachingVehicles[0]!.forecast, "seatAvailableProbability", value);
      expect(homeFavoritesFrom(invalid)).toBeNull();
    }
    const zero = structuredClone(home);
    zero.groups[0]!.favorites[0]!.approachingVehicles[0]!.forecast = {
      status: "AVAILABLE",
      seatAvailableProbability: 0,
      expectedSeats: 0,
    };
    expect(homeFavoritesFrom(zero)).toEqual(zero);
  });
  it("키가 중복된 그룹·즐겨찾기를 순서 편집 데이터로 받지 않는다", () => {
    const group = { id, name: "출근", favorites: [] };
    expect(favoriteGroupsFrom({ groups: [group, group] })).toBeNull();
    const invalid = structuredClone(home);
    invalid.groups[0]!.favorites.push(invalid.groups[0]!.favorites[0]!);
    expect(homeFavoritesFrom(invalid)).toBeNull();
  });
  it.each([3, 4])("그룹당 즐겨찾기 %i개 응답은 조회 허용 한도 3개로 검증한다", (count) => {
    const favorites = Array.from({ length: count }, (_, index) => ({
      ...home.groups[0]!.favorites[0]!,
      id: `10000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
    }));
    const overview = homeFavoritesFrom({ groups: [{ id, name: "출근", favorites }] });
    const groups = favoriteGroupsFrom({
      groups: [
        {
          id,
          name: "출근",
          favorites: favorites.map((favorite) => ({
            id: favorite.id,
            routeId: favorite.route.id,
            stopId: favorite.stop.stopId,
            direction: favorite.stop.direction,
          })),
        },
      ],
    });
    if (count === 3) {
      expect(overview?.groups[0]?.favorites).toHaveLength(3);
      expect(groups?.groups[0]?.favorites).toHaveLength(3);
    } else {
      expect(overview).toBeNull();
      expect(groups).toBeNull();
    }
  });
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
  it("즐겨찾기도 requestId 없는 오류는 잘못된 응답으로 분류한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockImplementation(
        async () =>
          new Response(JSON.stringify({ code: "LAST_FAVORITE_GROUP", message: "마지막 그룹" }), { status: 409 }),
      );
    expect(await saveGroupOrder([id])).toMatchObject({
      ok: false,
      failure: { kind: "malformed", status: 409 },
    });
    expect(await requestJson("/api/me")).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
  it("requestId가 있는 즐겨찾기 오류는 계약 오류로 읽는다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ code: "LAST_FAVORITE_GROUP", message: "마지막 그룹", requestId: "request-1" }), {
        status: 409,
      }),
    );
    expect(await saveGroupOrder([id])).toMatchObject({
      ok: false,
      failure: { kind: "contract", error: { code: "LAST_FAVORITE_GROUP", requestId: "request-1" } },
    });
  });
  it("깨진 그룹 성공 응답을 회원 데이터로 받아들이지 않는다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({ groups: [{ id: "invalid", name: "출근", favorites: [] }] })));
    expect(await fetchFavoriteGroups()).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
  it("남은 정류장 수가 0 이상의 정수가 아니거나 없으면 거절한다", () => {
    const missing = structuredClone(home);
    Reflect.deleteProperty(missing.groups[0]!.favorites[0]!.approachingVehicles[0]!, "remainingStops");
    expect(homeFavoritesFrom(missing)).toBeNull();
    for (const invalid of [-1, 1.5, "3", null]) {
      const response = structuredClone(home);
      Reflect.set(response.groups[0]!.favorites[0]!.approachingVehicles[0]!, "remainingStops", invalid);
      expect(homeFavoritesFrom(response)).toBeNull();
    }
  });
  it("ETA가 없는 차량의 좌석 예보는 유효하며 잘못된 확률과 ETA는 거절한다", () => {
    expect(homeFavoritesFrom(home)).not.toBeNull();
    const invalid = structuredClone(home);
    invalid.groups[0]!.favorites[0]!.approachingVehicles[0]!.forecast.seatAvailableProbability = 1.1;
    expect(homeFavoritesFrom(invalid)).toBeNull();
    expect(homeFavoritesFrom({ groups: [] })).not.toBeNull();
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
      homeFavoritesFrom({ groups: [{ ...home.groups[0], favorites: [home.groups[0]!.favorites[0], unavailable] }] }),
    ).not.toBeNull();
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
    expect(homeFavoritesFrom(response)).not.toBeNull();
    for (const invalid of [null, 42, {}, []]) {
      Reflect.set(value, field, invalid);
      expect(homeFavoritesFrom(response)).toBeNull();
    }
    if (field === "direction") {
      Reflect.set(value, field, "UNKNOWN");
      expect(homeFavoritesFrom(response)).toBeNull();
    }
  });
});
