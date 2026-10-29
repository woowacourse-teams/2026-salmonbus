import { describe, expect, it } from "@jest/globals";
import { isAnalyticsOptOutChange, loadAnalyticsOptOut, storeAnalyticsOptOut } from "./optOutStorage";

const STORAGE_KEY = "salmonbus.analyticsOptOut";

function createMemoryStorage() {
  const items = new Map<string, string>();
  return {
    items,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => void items.set(key, value),
    removeItem: (key: string) => void items.delete(key),
  };
}

const blockedStorage = () => {
  throw new DOMException("blocked", "SecurityError");
};

describe("분석 끄기 저장", () => {
  it("기록이 없으면 켜진 상태다", () => {
    const storage = createMemoryStorage();
    expect(loadAnalyticsOptOut(() => storage)).toBe(false);
  });

  it("끄면 salmonbus.analyticsOptOut에 남기고, 다시 켜면 지운다", () => {
    const storage = createMemoryStorage();
    storeAnalyticsOptOut(true, () => storage);
    expect(storage.items.get(STORAGE_KEY)).toBe("true");
    expect(loadAnalyticsOptOut(() => storage)).toBe(true);

    storeAnalyticsOptOut(false, () => storage);
    expect(storage.items.has(STORAGE_KEY)).toBe(false);
    expect(loadAnalyticsOptOut(() => storage)).toBe(false);
  });

  it("저장소를 막은 브라우저에서는 켜진 상태로 읽고, 쓰기 실패를 던지지 않는다", () => {
    expect(loadAnalyticsOptOut(blockedStorage)).toBe(false);
    expect(() => storeAnalyticsOptOut(true, blockedStorage)).not.toThrow();
  });
});

describe("다른 탭의 저장소 변경", () => {
  it("분석 끄기 키가 바뀌거나 저장소가 비워지면 설정이 바뀐 것으로 본다", () => {
    expect(isAnalyticsOptOutChange({ key: STORAGE_KEY })).toBe(true);
    expect(isAnalyticsOptOutChange({ key: null })).toBe(true);
  });

  it("다른 키가 바뀐 것은 무시한다", () => {
    expect(isAnalyticsOptOutChange({ key: "salmonbus.routeFirstSeen" })).toBe(false);
  });
});
