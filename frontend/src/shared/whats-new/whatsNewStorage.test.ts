import { describe, expect, it } from "@jest/globals";
import type { WhatsNewNote } from "./whatsNewNotes";
import {
  loadWhatsNewRecord,
  recordNewRoutesSeen,
  recordWhatsNewClosed,
  recordWhatsNewListOpened,
  recordWhatsNewShown,
} from "./whatsNewStorage";

const STORAGE_KEY = "salmonbus.whatsNew";
const ROUTE_FIRST_SEEN_KEY = "salmonbus.routeFirstSeen";
const NOV_14_MORNING = Date.parse("2026-11-14T08:00:00+09:00");
const NOV_14_END = Date.parse("2026-11-14T23:59:59.999+09:00");

const notes: readonly WhatsNewNote[] = [
  { id: 2, publishedAt: "2026-11-12", features: ["기능 2"] },
  { id: 1, publishedAt: "2026-11-02", features: ["기능 1"] },
];

function createMemoryStorage(entries: Record<string, string> = {}) {
  const items = new Map(Object.entries(entries));
  return {
    items,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => {
      items.set(key, value);
    },
  };
}

const storedRecordOf = (storage: ReturnType<typeof createMemoryStorage>): unknown =>
  JSON.parse(storage.items.get(STORAGE_KEY) ?? "null");

describe("loadWhatsNewRecord", () => {
  it("처음 온 기기는 지금까지의 소식을 본 것으로 salmonbus.whatsNew에 저장한다", () => {
    const storage = createMemoryStorage();

    const record = loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING);

    expect(record).toEqual({ lastSeenId: 2, lastShownDay: null, routesSeenUntil: NOV_14_END });
    expect(storedRecordOf(storage)).toEqual(record);
  });

  it("NEW 배지 기록이 있는 기기는 전부터 쓰던 기기로 보고 0번부터 센다", () => {
    const storage = createMemoryStorage({ [ROUTE_FIRST_SEEN_KEY]: '{"A":0}' });

    const record = loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING);

    expect(record).toEqual({ lastSeenId: 0, lastShownDay: null, routesSeenUntil: NOV_14_END });
    expect(storedRecordOf(storage)).toEqual(record);
  });

  it("저장한 기록이 있으면 그대로 읽고 다시 쓰지 않는다", () => {
    const routesSeenUntil = Date.parse("2026-11-13T09:00:00+09:00");
    const stored = JSON.stringify({ lastSeenId: 1, lastShownDay: "2026-11-13", routesSeenUntil });
    const storage = createMemoryStorage({ [STORAGE_KEY]: stored });

    expect(loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING)).toEqual({
      lastSeenId: 1,
      lastShownDay: "2026-11-13",
      routesSeenUntil,
    });
    expect(storage.items.get(STORAGE_KEY)).toBe(stored);
  });

  it("다시 보지 않기가 있던 때 저장한 기록도 읽고, 자동 표시 값은 버린다", () => {
    const storage = createMemoryStorage({
      [STORAGE_KEY]: JSON.stringify({ lastSeenId: 1, autoShow: false, lastShownDay: "2026-11-13" }),
    });

    expect(loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING)).toEqual({
      lastSeenId: 1,
      lastShownDay: "2026-11-13",
      routesSeenUntil: NOV_14_END,
    });
  });

  it("노선 기준 시각이 없는 예전 기록은 나머지 값을 그대로 두고, 처음 읽은 날 끝을 노선 기준으로 채워 저장한다", () => {
    const storage = createMemoryStorage({
      [STORAGE_KEY]: JSON.stringify({ lastSeenId: 1, lastShownDay: "2026-11-13" }),
    });
    const upgraded = { lastSeenId: 1, lastShownDay: "2026-11-13", routesSeenUntil: NOV_14_END };

    expect(loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING)).toEqual(upgraded);
    expect(storedRecordOf(storage)).toEqual(upgraded);
    expect(loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING + 24 * 60 * 60_000)).toEqual(upgraded);
  });

  it.each([
    { kind: "JSON이 아닌 글자", storedText: "{oops" },
    { kind: "배열", storedText: "[1]" },
    { kind: "음수 번호", storedText: '{"lastSeenId":-1,"lastShownDay":null}' },
    { kind: "날짜 모양이 아닌 날", storedText: '{"lastSeenId":1,"lastShownDay":"11월 14일"}' },
    { kind: "음수 routesSeenUntil", storedText: '{"lastSeenId":1,"lastShownDay":null,"routesSeenUntil":-1}' },
    { kind: "소수 routesSeenUntil", storedText: '{"lastSeenId":1,"lastShownDay":null,"routesSeenUntil":1.5}' },
    {
      kind: "숫자가 아닌 routesSeenUntil",
      storedText: '{"lastSeenId":1,"lastShownDay":null,"routesSeenUntil":"2026-11-14"}',
    },
  ])("$kind로 깨진 기록은 처음 온 기기로 보고 덮어쓴다", ({ storedText }) => {
    const storage = createMemoryStorage({ [STORAGE_KEY]: storedText });
    const firstRecord = { lastSeenId: 2, lastShownDay: null, routesSeenUntil: NOV_14_END };

    expect(loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING)).toEqual(firstRecord);
    expect(storedRecordOf(storage)).toEqual(firstRecord);
  });

  it("저장소를 쓸 수 없으면 기록 없이 돌아가 모달을 띄우지 않는다", () => {
    const openBlockedStorage = () => {
      throw new Error("SecurityError");
    };

    expect(loadWhatsNewRecord(notes, openBlockedStorage)).toBeNull();
  });
});

describe("recordWhatsNewShown", () => {
  it("띄운 날을 한국 날짜로 적는다", () => {
    const storage = createMemoryStorage();
    loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING);

    recordWhatsNewShown(Date.parse("2026-11-14T23:30:00+09:00"), () => storage);

    expect(storedRecordOf(storage)).toEqual({ lastSeenId: 2, lastShownDay: "2026-11-14", routesSeenUntil: NOV_14_END });
  });

  it("기록이 없으면 아무것도 쓰지 않는다", () => {
    const storage = createMemoryStorage();

    recordWhatsNewShown(NOV_14_MORNING, () => storage);

    expect(storage.items.size).toBe(0);
  });
});

describe("recordWhatsNewClosed", () => {
  it("닫으면 보여 준 소식까지 본 것으로 적는다", () => {
    const storage = createMemoryStorage({ [ROUTE_FIRST_SEEN_KEY]: "{}" });
    loadWhatsNewRecord(notes, () => storage, NOV_14_MORNING);

    recordWhatsNewClosed(notes, () => storage);

    expect(storedRecordOf(storage)).toEqual({ lastSeenId: 2, lastShownDay: null, routesSeenUntil: NOV_14_END });
  });

  it("저장하다 예외가 나도 화면을 멈추지 않는다", () => {
    const stored = JSON.stringify({ lastSeenId: 0, lastShownDay: null });
    const storage = {
      getItem: () => stored,
      setItem: () => {
        throw new Error("QuotaExceededError");
      },
    };

    expect(() => recordWhatsNewClosed(notes, () => storage)).not.toThrow();
  });
});

describe("recordWhatsNewListOpened", () => {
  it("목록을 열면 가장 큰 번호까지 본 것으로 적는다", () => {
    const storage = createMemoryStorage({ [ROUTE_FIRST_SEEN_KEY]: "{}" });
    loadWhatsNewRecord(notes, () => storage);

    recordWhatsNewListOpened(notes, () => storage);

    expect(storedRecordOf(storage)).toEqual({ lastSeenId: 2, lastShownDay: null, routesSeenUntil: expect.any(Number) });
  });

  it("홈보다 목록을 먼저 연 전부터 쓰던 기기도 가장 큰 번호까지 본 것으로 적는다", () => {
    const storage = createMemoryStorage({ [ROUTE_FIRST_SEEN_KEY]: "{}" });

    recordWhatsNewListOpened(notes, () => storage);

    expect(storedRecordOf(storage)).toEqual({ lastSeenId: 2, lastShownDay: null, routesSeenUntil: expect.any(Number) });
  });
});

describe("recordNewRoutesSeen", () => {
  it("보여 준 노선 목록을 받은 시각까지 본 것으로 적는다", () => {
    const storage = createMemoryStorage({ [ROUTE_FIRST_SEEN_KEY]: "{}" });
    loadWhatsNewRecord(notes, () => storage, Date.parse("2026-11-13T08:00:00+09:00"));

    recordNewRoutesSeen(NOV_14_MORNING, () => storage);

    expect(storedRecordOf(storage)).toEqual({ lastSeenId: 0, lastShownDay: null, routesSeenUntil: NOV_14_MORNING });
  });

  it("기록이 없으면 아무것도 쓰지 않는다", () => {
    const storage = createMemoryStorage();

    recordNewRoutesSeen(NOV_14_MORNING, () => storage);

    expect(storage.items.size).toBe(0);
  });
});
