import { describe, expect, it } from "@jest/globals";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { newRouteIdsFor } from "./newRoutePolicy";
import { recordSeenRoutes } from "./seenRouteStorage";

const STORAGE_KEY = "salmonbus.routeFirstSeen";
const AFTER_DEADLINE = Date.parse("2027-01-10T08:00:00+09:00");
const HOUR = 60 * 60_000;

function createRoute(id: string): RouteSummary {
  return { id, displayName: id, startStopName: "기점", endStopName: "종점", status: "FORECAST_READY" };
}

const routeA = createRoute("A");
const routeB = createRoute("B");
const routeC = createRoute("C");

function visit(routes: readonly RouteSummary[], now: number, openStorage: () => Pick<Storage, "getItem" | "setItem">) {
  return newRouteIdsFor(recordSeenRoutes(routes, now, openStorage), now);
}

function createMemoryStorage(storedText: string | null = null) {
  const items = new Map<string, string>();
  if (storedText !== null) items.set(STORAGE_KEY, storedText);
  const writtenKeys: string[] = [];
  return {
    items,
    writtenKeys,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => {
      writtenKeys.push(key);
      items.set(key, value);
    },
  };
}

describe("recordSeenRoutes", () => {
  it("기록을 salmonbus.routeFirstSeen 키 하나에 저장한다", () => {
    const storage = createMemoryStorage();

    recordSeenRoutes([routeA], AFTER_DEADLINE, () => storage);

    expect([...storage.items.keys()]).toEqual([STORAGE_KEY]);
  });

  it("저장한 기록을 다음 방문에 다시 읽어 새로 생긴 노선만 NEW로 돌려준다", () => {
    const storage = createMemoryStorage();
    const openStorage = () => storage;

    expect(visit([routeA], AFTER_DEADLINE, openStorage)).toEqual(new Set());
    expect(visit([routeA, routeB], AFTER_DEADLINE + HOUR, openStorage)).toEqual(new Set(["B"]));
    expect(visit([routeA, routeB], AFTER_DEADLINE + 2 * HOUR, openStorage)).toEqual(new Set(["B"]));
  });

  it.each([
    { kind: "JSON이 아닌 글자", storedText: "{oops" },
    { kind: "JSON null", storedText: "null" },
    { kind: "배열", storedText: "[0]" },
    { kind: "음수 시각", storedText: '{"A":-1}' },
    { kind: "소수 시각", storedText: '{"A":1.5}' },
    { kind: "숫자가 아닌 시각", storedText: '{"A":"2027-01-01"}' },
  ])("$kind로 깨진 기록은 첫 방문으로 보고 덮어쓴다", ({ storedText }) => {
    const storage = createMemoryStorage(storedText);
    const openStorage = () => storage;

    expect(visit([routeA, routeB], AFTER_DEADLINE, openStorage)).toEqual(new Set());
    expect(visit([routeA, routeB, routeC], AFTER_DEADLINE + HOUR, openStorage)).toEqual(new Set(["C"]));
  });

  describe("저장소를 쓸 수 없으면 NEW를 하나도 붙이지 않는다", () => {
    it("저장소를 꺼내다 예외가 나도 화면을 멈추지 않는다", () => {
      const openBlockedStorage = () => {
        throw new Error("SecurityError");
      };

      expect(visit([routeA, routeB], AFTER_DEADLINE, openBlockedStorage)).toEqual(new Set());
    });

    it("기록을 읽다 예외가 나면 저장하지 않는다", () => {
      const storage = createMemoryStorage();
      const unreadableStorage = {
        ...storage,
        getItem: () => {
          throw new Error("SecurityError");
        },
      };

      expect(visit([routeA, routeB], AFTER_DEADLINE, () => unreadableStorage)).toEqual(new Set());
      expect(storage.writtenKeys).toEqual([]);
    });

    it("저장하다 예외가 나면 NEW로 계산한 노선이 있어도 붙이지 않는다", () => {
      const storage = createMemoryStorage();
      recordSeenRoutes([routeA], AFTER_DEADLINE, () => storage);
      const fullStorage = {
        ...storage,
        setItem: () => {
          throw new Error("QuotaExceededError");
        },
      };

      expect(visit([routeA, routeB], AFTER_DEADLINE + HOUR, () => fullStorage)).toEqual(new Set());
      expect(visit([routeA, routeB], AFTER_DEADLINE + HOUR, () => storage)).toEqual(new Set(["B"]));
    });
  });
});
