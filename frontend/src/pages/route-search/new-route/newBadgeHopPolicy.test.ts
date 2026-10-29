import { describe, expect, it } from "@jest/globals";
import type { ListedRoute } from "../displayPolicy";
import { hoppingBadgeIndexFor, newBadgeHopKeyFor } from "./newBadgeHopPolicy";

function createRoute(displayName: string, isNew: boolean): ListedRoute {
  return { id: displayName, displayName, startStopName: "기점", endStopName: "종점", status: "FORECAST_READY", isNew };
}

const newRoute = createRoute("3000", true);
const knownRoute = createRoute("3330", false);

describe("newBadgeHopKeyFor", () => {
  it("입력 전 목록에 새 노선이 있으면 빈 입력을 키로 쓴다", () => {
    expect(newBadgeHopKeyFor({ kind: "all", routes: [knownRoute, newRoute] }, "")).toBe("");
  });

  it("입력 중 결과에 새 노선이 있으면 친 숫자를 키로 쓴다", () => {
    expect(newBadgeHopKeyFor({ kind: "matched", routes: [newRoute] }, "30번")).toBe("30");
  });

  it("보이는 노선에 새 노선이 없으면 뛰지 않는다", () => {
    expect(newBadgeHopKeyFor({ kind: "matched", routes: [knownRoute] }, "33")).toBeNull();
  });

  it("노선 목록을 보여 주지 않는 화면이면 뛰지 않는다", () => {
    expect(newBadgeHopKeyFor({ kind: "loading" }, "")).toBeNull();
    expect(newBadgeHopKeyFor({ kind: "error" }, "")).toBeNull();
    expect(newBadgeHopKeyFor({ kind: "empty" }, "")).toBeNull();
    expect(newBadgeHopKeyFor({ kind: "unmatched" }, "7")).toBeNull();
  });
});

describe("hoppingBadgeIndexFor", () => {
  const visibleArea = { top: 100, bottom: 400 };

  function badgeAt(top: number) {
    return { top, bottom: top + 15 };
  }

  it("보이는 영역 안의 배지 중 가운데 순서의 배지를 고른다", () => {
    expect(hoppingBadgeIndexFor([badgeAt(120), badgeAt(170), badgeAt(220)], visibleArea)).toBe(1);
  });

  it("보이는 배지가 짝수 개면 가운데 두 개 중 위쪽을 고른다", () => {
    expect(hoppingBadgeIndexFor([badgeAt(120), badgeAt(170), badgeAt(220), badgeAt(270)], visibleArea)).toBe(1);
  });

  it("보이는 영역 밖이나 걸쳐 있는 배지는 세지 않는다", () => {
    const badges = [badgeAt(50), badgeAt(95), badgeAt(120), badgeAt(170), badgeAt(390), badgeAt(500)];

    expect(hoppingBadgeIndexFor(badges, visibleArea)).toBe(2);
  });

  it("보이는 배지가 하나면 그 배지를 고른다", () => {
    expect(hoppingBadgeIndexFor([badgeAt(20), badgeAt(300), badgeAt(600)], visibleArea)).toBe(1);
  });

  it("보이는 배지가 없으면 고르지 않는다", () => {
    expect(hoppingBadgeIndexFor([badgeAt(20), badgeAt(600)], visibleArea)).toBeNull();
  });
});
