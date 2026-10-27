import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { isRecord } from "@/shared/utils/valueGuards";
import { firstSeenTimesFor, type FirstSeenTimes } from "./newRoutePolicy";

const STORAGE_KEY = "salmonbus.routeFirstSeen";

type SeenRouteStorage = Pick<Storage, "getItem" | "setItem">;

// 저장소를 막아 둔 브라우저는 localStorage를 꺼내기만 해도 예외를 던져서 꺼내는 일도 try 안에서 한다.
const openLocalStorage = () => window.localStorage;

export function recordSeenRoutes(
  routes: readonly RouteSummary[],
  now: number,
  openStorage: () => SeenRouteStorage = openLocalStorage,
): FirstSeenTimes {
  try {
    const storage = openStorage();
    const stored = firstSeenTimesFrom(storage.getItem(STORAGE_KEY));
    const next = firstSeenTimesFor(routes, stored, now);
    if (next.size > stored.size) storage.setItem(STORAGE_KEY, JSON.stringify(Object.fromEntries(next)));
    return next;
  } catch {
    return new Map();
  }
}

function firstSeenTimesFrom(text: string | null): FirstSeenTimes {
  if (text === null) return new Map();
  try {
    const value: unknown = JSON.parse(text);
    if (!isRecord(value) || Array.isArray(value)) return new Map();
    const times = new Map<string, number>();
    for (const [id, seenAt] of Object.entries(value)) {
      if (typeof seenAt !== "number" || !Number.isSafeInteger(seenAt) || seenAt < 0) return new Map();
      times.set(id, seenAt);
    }
    return times;
  } catch {
    return new Map();
  }
}
