import { describe, expect, it } from "@jest/globals";
import type { LatestSuccess } from "@/shared/api/polledResource";
import type { RouteWatch } from "@/shared/api/routeForecast.types";
import { boardWith } from "@/testing/fixtures";
import { expiredAtReceiptOf } from "./useFreshLiveVehicles";

function latestOf(overrides: {
  staleAt?: string;
  receivedAt?: number;
  servedAt?: number | null;
}): LatestSuccess<RouteWatch> {
  const board = boardWith({
    staleAt: overrides.staleAt ?? "2026-09-01T08:05:00+09:00",
  });
  const servedAt = overrides.servedAt === undefined ? Date.parse("2026-09-01T08:00:00+09:00") : overrides.servedAt;
  return {
    body: { ...board, vehicles: [] },
    clock: servedAt === null ? null : { servedAt, now: () => servedAt },
    receivedAt: overrides.receivedAt ?? Date.parse("2026-09-01T08:00:00+09:00"),
  };
}

describe("expiredAtReceiptOf", () => {
  it("staleAt을 못 읽으면 만료로 본다", () => {
    expect(expiredAtReceiptOf(latestOf({ staleAt: "not-a-date" }))).toBe(true);
  });

  it("staleAt이 clock.servedAt 이하면 만료다", () => {
    expect(
      expiredAtReceiptOf(
        latestOf({
          staleAt: "2026-09-01T08:00:00+09:00",
          servedAt: Date.parse("2026-09-01T08:00:00+09:00"),
        }),
      ),
    ).toBe(true);
    expect(
      expiredAtReceiptOf(
        latestOf({
          staleAt: "2026-09-01T07:59:00+09:00",
          servedAt: Date.parse("2026-09-01T08:00:00+09:00"),
        }),
      ),
    ).toBe(true);
  });

  it("clock이 없으면 receivedAt으로 비교한다", () => {
    expect(
      expiredAtReceiptOf(
        latestOf({
          staleAt: "2026-09-01T08:00:00+09:00",
          servedAt: null,
          receivedAt: Date.parse("2026-09-01T08:00:00+09:00"),
        }),
      ),
    ).toBe(true);
    expect(
      expiredAtReceiptOf(
        latestOf({
          staleAt: "2026-09-01T08:05:00+09:00",
          servedAt: null,
          receivedAt: Date.parse("2026-09-01T08:00:00+09:00"),
        }),
      ),
    ).toBe(false);
  });

  it("미래 staleAt은 만료가 아니다", () => {
    expect(
      expiredAtReceiptOf(
        latestOf({
          staleAt: "2026-09-01T08:05:00+09:00",
          servedAt: Date.parse("2026-09-01T08:00:00+09:00"),
        }),
      ),
    ).toBe(false);
  });
});
