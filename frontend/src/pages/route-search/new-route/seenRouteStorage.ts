import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { isRecord } from "@/shared/utils/valueGuards";
import { firstSeenTimesFor, newRouteIdsFor, type FirstSeenTimes } from "./newRoutePolicy";

const STORAGE_KEY = "salmonbus.routeFirstSeen";

type SeenRouteStorage = Pick<Storage, "getItem" | "setItem">;

// 저장소를 막아 둔 브라우저는 localStorage를 꺼내기만 해도 예외를 던져서 꺼내는 일도 try 안에서 한다.
const openLocalStorage = () => window.localStorage;

export function recordSeenRoutes(
  routes: readonly RouteSummary[],
  now: number,
  openStorage: () => SeenRouteStorage = openLocalStorage,
): ReadonlySet<string> {
  try {
    const storage = openStorage();
    const stored = firstSeenTimesFrom(storage.getItem(STORAGE_KEY));
    const next = firstSeenTimesFor(routes, stored, now);
    if (next.size > stored.size) storage.setItem(STORAGE_KEY, JSON.stringify(Object.fromEntries(next)));
    return newRouteIdsFor(next, now);
  } catch {
    return new Set();
  }
}

function firstSeenTimesFrom(text: string | null): FirstSeenTimes {
  if (text === null) return new Map();
  try {
    const value: unknown = JSON.parse(text);
    if (!isRecord(value) || Array.isArray(value)) return new Map();
    const entries = Object.entries(value);
    return entries.every(isFirstSeenEntry) ? new Map(entries) : new Map();
  } catch {
    return new Map();
  }
}

function isFirstSeenEntry(entry: [string, unknown]): entry is [string, number] {
  const [, seenAt] = entry;
  return typeof seenAt === "number" && Number.isSafeInteger(seenAt) && seenAt >= 0;
}
