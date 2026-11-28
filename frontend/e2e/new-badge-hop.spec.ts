import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures/api";
import { openRouteSearch, routeSearchInput } from "./utils/route-select";

test.use({ viewport: { width: 390, height: 844 } });

const newRoutes = ["3000", "3100", "3500", "5600"].map((displayName, index) => ({
  id: String(200000010 + index),
  displayName,
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY" as const,
}));

function hoppingBadgesOf(page: Page) {
  return page
    .getByRole("main")
    .getByText("NEW", { exact: true })
    .evaluateAll((badges) =>
      badges
        .filter((badge) => badge.getAnimations().length > 0)
        .map((badge) => badge.closest("button")?.getAttribute("aria-label")),
    );
}

// page.clock은 타이머만 멈추고 Web Animations 진행은 멈추지 못해서, 끝나기는 실제 시간으로 기다린다.
async function waitForHopToEnd(page: Page) {
  await expect.poll(() => hoppingBadgesOf(page)).toEqual([]);
}

// 전에 다른 노선만 본 기기라서, 이번 방문에 처음 보는 노선이 모두 NEW다. 출시 기간 규칙에 기대지 않는다.
test.beforeEach(async ({ page, api }) => {
  api.data.routes.routes.push(...newRoutes);
  await page.addInitScript(() => {
    if (localStorage.getItem("salmonbus.routeFirstSeen") !== null) return;
    localStorage.setItem("salmonbus.routeFirstSeen", JSON.stringify({ "234000050": 0 }));
  });
});

test("입력 전 목록이 보이고 1초가 지나면 보이는 NEW 배지 가운데 하나만 한 번 뛴다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  await openRouteSearch(page);
  await expect(page.getByRole("main").getByText("NEW", { exact: true })).toHaveCount(5);

  // 검색 화면을 여는 동안 시계가 100ms 흐르고, 그 사이 노선이 먼저 도착하면 타이머도 그만큼 먼저 시작된다.
  await page.clock.runFor(800);
  expect(await hoppingBadgesOf(page)).toEqual([]);

  await page.clock.runFor(200);
  expect(await hoppingBadgesOf(page)).toEqual(["3330번 노선, 새 노선, 테스트 기점부터 테스트 종점 구간"]);
  await waitForHopToEnd(page);

  await test.step("번호를 치는 동안에는 뛰지 않고 입력이 1초 멈추면 뛴다", async () => {
    await routeSearchInput(page).fill("3");
    await page.clock.runFor(600);
    await routeSearchInput(page).fill("35");
    await page.clock.runFor(600);
    expect(await hoppingBadgesOf(page)).toEqual([]);

    await page.clock.runFor(400);
    expect(await hoppingBadgesOf(page)).toEqual(["3500번 노선, 새 노선, 기점부터 종점 구간"]);
    await waitForHopToEnd(page);
  });

  await test.step("이미 뛴 입력으로 돌아오면 다시 뛰지 않는다", async () => {
    await routeSearchInput(page).fill("");
    await page.clock.runFor(1_000);
    expect(await hoppingBadgesOf(page)).toEqual([]);
  });

  await test.step("동작 줄이기 설정이면 아직 안 뛴 입력에서도 뛰지 않는다", async () => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await routeSearchInput(page).fill("31");
    await page.clock.runFor(1_000);
    expect(await hoppingBadgesOf(page)).toEqual([]);
  });
});
