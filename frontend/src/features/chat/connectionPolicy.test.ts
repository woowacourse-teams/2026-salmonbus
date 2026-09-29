import { describe, expect, it } from "@jest/globals";
import { reconnectDelayMs, shouldReconnect } from "./connectionPolicy";

describe("reconnectDelayMs", () => {
  it("재연결은 1, 2, 4, 8, 16초 뒤 다섯 번만 시도한다", () => {
    expect([0, 1, 2, 3, 4, 5].map(reconnectDelayMs)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, null]);
  });
});

describe("shouldReconnect", () => {
  it("지원하지 않는 노선(1003)과 치명 오류(1008)로 닫히면 다시 잇지 않는다", () => {
    expect(shouldReconnect(1003)).toBe(false);
    expect(shouldReconnect(1008)).toBe(false);
  });

  it("연결 수 초과, 서버 재시작, 서버 오류, 비활성, 네트워크 끊김으로 닫히면 다시 잇는다", () => {
    expect([1013, 1001, 1011, 4500, 1006].map(shouldReconnect)).toEqual([true, true, true, true, true]);
  });
});
