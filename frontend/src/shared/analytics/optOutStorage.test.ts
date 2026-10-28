import { describe, expect, it } from "@jest/globals";
import { isAnalyticsOptedOut, storeAnalyticsOptOut } from "./optOutStorage";

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
    expect(isAnalyticsOptedOut(() => storage)).toBe(false);
  });

  it("끄면 salmonbus.analyticsOptOut에 남기고, 다시 켜면 지운다", () => {
    const storage = createMemoryStorage();
    storeAnalyticsOptOut(true, () => storage);
    expect(storage.items.get(STORAGE_KEY)).toBe("true");
    expect(isAnalyticsOptedOut(() => storage)).toBe(true);

    storeAnalyticsOptOut(false, () => storage);
    expect(storage.items.has(STORAGE_KEY)).toBe(false);
    expect(isAnalyticsOptedOut(() => storage)).toBe(false);
  });

  it("저장소를 막은 브라우저에서는 켜진 상태로 읽고, 쓰기 실패를 던지지 않는다", () => {
    expect(isAnalyticsOptedOut(blockedStorage)).toBe(false);
    expect(() => storeAnalyticsOptOut(true, blockedStorage)).not.toThrow();
  });
});
