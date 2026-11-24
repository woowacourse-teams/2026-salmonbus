import { describe, expect, it } from "@jest/globals";
import type { FavoriteStatus, HomeFavorite } from "@/shared/api/favorites.types";
import { favoriteCardViewFor } from "./favoriteCardView";

type Card = Pick<HomeFavorite, "status" | "route" | "stop">;

function named(status: FavoriteStatus, direction?: "UP" | "DOWN"): Card {
  return {
    status,
    route: { id: "200000001", displayName: "3330" },
    stop: direction ? { stopId: "10011", name: "판교역", direction } : { stopId: "10011", name: "판교역" },
  };
}

function unnamed(status: FavoriteStatus): Card {
  return { status, route: { id: "200000001" }, stop: { stopId: "10011" } };
}

describe("favoriteCardViewFor", () => {
  it("정류장 정보를 불러오지 못한 항목은 이름이 있어도 재시도 카드다", () => {
    expect(favoriteCardViewFor(named("ERROR", "UP"), "UP")).toEqual({ kind: "retry" });
    expect(favoriteCardViewFor(unnamed("ERROR"), null)).toEqual({ kind: "retry" });
  });

  it.each(["READY", "NO_VEHICLE", "PREPARING", "AMBIGUOUS", "UNAVAILABLE"] as const)(
    "%s 항목은 노선과 정류장 이름이 있으면 판정 보드로 가는 링크 카드다",
    (status) => {
      expect(favoriteCardViewFor(named(status, "UP"), null)).toEqual({
        kind: "link",
        routeName: "3330",
        stopName: "판교역",
        direction: "UP",
      });
    },
  );

  it("응답에 방면이 없으면 저장한 방면을 쓴다", () => {
    expect(favoriteCardViewFor(named("READY"), "DOWN")).toMatchObject({ kind: "link", direction: "DOWN" });
    expect(favoriteCardViewFor(unnamed("AMBIGUOUS"), "DOWN")).toEqual({
      kind: "notice",
      status: "AMBIGUOUS",
      direction: "DOWN",
    });
  });

  it("응답에 방면이 있으면 저장한 방면보다 응답을 따른다", () => {
    expect(favoriteCardViewFor(named("READY", "UP"), "DOWN")).toMatchObject({ direction: "UP" });
  });

  it("응답에도 저장한 것에도 방면이 없으면 방면 없이 그린다", () => {
    expect(favoriteCardViewFor(named("READY"), null)).toMatchObject({ kind: "link", direction: null });
  });

  it("노선이나 정류장 이름이 빠진 항목은 상태를 알리는 안내 카드다", () => {
    expect(favoriteCardViewFor(unnamed("NO_VEHICLE"), "UP")).toEqual({
      kind: "notice",
      status: "NO_VEHICLE",
      direction: "UP",
    });
    expect(favoriteCardViewFor({ ...named("PREPARING", "UP"), route: { id: "200000001" } }, null)).toMatchObject({
      kind: "notice",
      status: "PREPARING",
    });
  });

  it("이용할 수 없는 정류장의 안내 카드에는 방면을 붙이지 않는다", () => {
    expect(favoriteCardViewFor(unnamed("UNAVAILABLE"), "UP")).toEqual({
      kind: "notice",
      status: "UNAVAILABLE",
      direction: null,
    });
  });

  it("이름이 빠진 READY 응답은 정보를 불러오지 못했다고 안내한다", () => {
    expect(favoriteCardViewFor(unnamed("READY"), "UP")).toEqual({ kind: "notice", status: "ERROR", direction: "UP" });
  });
});
