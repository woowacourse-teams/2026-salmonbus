import { ROUTE_FIRST_SEEN_KEY } from "@/shared/route-list/seenRouteStorage";
import { isRecord } from "@/shared/utils/valueGuards";
import type { WhatsNewNote } from "./whatsNewNotes";
import {
  closedRecordFor,
  firstRecordFor,
  firstRoutesSeenUntilFor,
  routesSeenRecordFor,
  shownRecordFor,
  type WhatsNewRecord,
} from "./whatsNewPolicy";

const STORAGE_KEY = "salmonbus.whatsNew";
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

type WhatsNewStorage = Pick<Storage, "getItem" | "setItem">;

type StoredWhatsNewRecord = Omit<WhatsNewRecord, "routesSeenUntil"> & { routesSeenUntil: number | null };

// 저장소를 막아 둔 브라우저는 localStorage를 꺼내기만 해도 예외를 던져서 꺼내는 일도 try 안에서 한다.
const openLocalStorage = () => window.localStorage;

export function loadWhatsNewRecord(
  notes: readonly WhatsNewNote[],
  openStorage: () => WhatsNewStorage = openLocalStorage,
  now = Date.now(),
): WhatsNewRecord | null {
  try {
    const storage = openStorage();
    const stored = whatsNewRecordFrom(storage.getItem(STORAGE_KEY));
    const record =
      stored === null
        ? firstRecordFor(notes, storage.getItem(ROUTE_FIRST_SEEN_KEY) !== null, now)
        : completedRecordFor(stored, now);
    if (stored === null || stored.routesSeenUntil === null) storage.setItem(STORAGE_KEY, JSON.stringify(record));
    return record;
  } catch {
    return null;
  }
}

export function recordWhatsNewShown(now: number, openStorage: () => WhatsNewStorage = openLocalStorage): void {
  recordWhatsNewWith((record) => shownRecordFor(record, now), openStorage);
}

export function recordWhatsNewClosed(
  shownNotes: readonly WhatsNewNote[],
  openStorage: () => WhatsNewStorage = openLocalStorage,
): void {
  recordWhatsNewWith((record) => closedRecordFor(record, shownNotes), openStorage);
}

// 목록에서 읽은 소식도 모달에서 닫은 소식처럼 본 것으로 적어 홈에 다시 띄우지 않는다.
// 홈보다 목록을 먼저 연 기기는 기록이 없어서 먼저 만든다. 그러지 않으면 홈에서 기록을 처음 만들 때 읽은 소식이 다시 뜬다.
export function recordWhatsNewListOpened(
  notes: readonly WhatsNewNote[],
  openStorage: () => WhatsNewStorage = openLocalStorage,
): void {
  loadWhatsNewRecord(notes, openStorage);
  recordWhatsNewWith((record) => closedRecordFor(record, notes), openStorage);
}

export function recordNewRoutesSeen(checkedAt: number, openStorage: () => WhatsNewStorage = openLocalStorage): void {
  recordWhatsNewWith((record) => routesSeenRecordFor(record, checkedAt), openStorage);
}

function recordWhatsNewWith(
  nextRecordFor: (record: WhatsNewRecord) => WhatsNewRecord,
  openStorage: () => WhatsNewStorage,
): void {
  try {
    const storage = openStorage();
    const stored = whatsNewRecordFrom(storage.getItem(STORAGE_KEY));
    if (stored === null) return;
    storage.setItem(STORAGE_KEY, JSON.stringify(nextRecordFor(completedRecordFor(stored, Date.now()))));
  } catch {
    // 저장소에 쓰지 못해도 화면은 계속 쓰게 둔다. 이때는 같은 소식이 홈에 올 때마다 다시 뜬다.
  }
}

// routesSeenUntil이 없는 예전 기록은 기록을 처음 만드는 진입처럼, 처음 읽은 날 끝을 노선 기준 시각으로 삼는다.
function completedRecordFor(stored: StoredWhatsNewRecord, now: number): WhatsNewRecord {
  return { ...stored, routesSeenUntil: stored.routesSeenUntil ?? firstRoutesSeenUntilFor(now) };
}

function whatsNewRecordFrom(text: string | null): StoredWhatsNewRecord | null {
  if (text === null) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!isRecord(value)) return null;
    const { lastSeenId, lastShownDay, routesSeenUntil = null } = value;
    if (typeof lastSeenId !== "number" || !Number.isSafeInteger(lastSeenId) || lastSeenId < 0) return null;
    if (lastShownDay !== null && (typeof lastShownDay !== "string" || !DAY_PATTERN.test(lastShownDay))) return null;
    if (
      routesSeenUntil !== null &&
      (typeof routesSeenUntil !== "number" || !Number.isSafeInteger(routesSeenUntil) || routesSeenUntil < 0)
    )
      return null;
    return { lastSeenId, lastShownDay, routesSeenUntil };
  } catch {
    return null;
  }
}
