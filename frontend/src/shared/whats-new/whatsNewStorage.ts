import { isRecord } from "@/shared/utils/valueGuards";
import type { WhatsNewNote } from "./whatsNewNotes";
import {
  closedRecordFor,
  firstRecordFor,
  shownRecordFor,
  type AutoShowChoice,
  type WhatsNewRecord,
} from "./whatsNewPolicy";

const STORAGE_KEY = "salmonbus.whatsNew";
const ROUTE_FIRST_SEEN_KEY = "salmonbus.routeFirstSeen";
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

type WhatsNewStorage = Pick<Storage, "getItem" | "setItem">;

// 저장소를 막아 둔 브라우저는 localStorage를 꺼내기만 해도 예외를 던져서 꺼내는 일도 try 안에서 한다.
const openLocalStorage = () => window.localStorage;

export function loadWhatsNewRecord(
  notes: readonly WhatsNewNote[],
  openStorage: () => WhatsNewStorage = openLocalStorage,
): WhatsNewRecord | null {
  try {
    const storage = openStorage();
    const stored = whatsNewRecordFrom(storage.getItem(STORAGE_KEY));
    if (stored !== null) return stored;
    const first = firstRecordFor(notes, storage.getItem(ROUTE_FIRST_SEEN_KEY) !== null);
    storage.setItem(STORAGE_KEY, JSON.stringify(first));
    return first;
  } catch {
    return null;
  }
}

export function recordWhatsNewShown(now: number, openStorage: () => WhatsNewStorage = openLocalStorage): void {
  recordWhatsNewWith((record) => shownRecordFor(record, now), openStorage);
}

export function recordWhatsNewClosed(
  shownNotes: readonly WhatsNewNote[],
  autoShow: AutoShowChoice,
  openStorage: () => WhatsNewStorage = openLocalStorage,
): void {
  recordWhatsNewWith((record) => closedRecordFor(record, shownNotes, autoShow), openStorage);
}

function recordWhatsNewWith(
  nextRecordFor: (record: WhatsNewRecord) => WhatsNewRecord,
  openStorage: () => WhatsNewStorage,
): void {
  try {
    const storage = openStorage();
    const stored = whatsNewRecordFrom(storage.getItem(STORAGE_KEY));
    if (stored === null) return;
    storage.setItem(STORAGE_KEY, JSON.stringify(nextRecordFor(stored)));
  } catch {
    // 저장소에 쓰지 못해도 화면은 계속 쓰게 둔다. 이때는 같은 소식이 홈에 올 때마다 다시 뜬다.
  }
}

function whatsNewRecordFrom(text: string | null): WhatsNewRecord | null {
  if (text === null) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!isRecord(value)) return null;
    const { lastSeenId, autoShow, lastShownDay } = value;
    if (typeof lastSeenId !== "number" || !Number.isSafeInteger(lastSeenId) || lastSeenId < 0) return null;
    if (typeof autoShow !== "boolean") return null;
    if (lastShownDay !== null && (typeof lastShownDay !== "string" || !DAY_PATTERN.test(lastShownDay))) return null;
    return { lastSeenId, autoShow, lastShownDay };
  } catch {
    return null;
  }
}
