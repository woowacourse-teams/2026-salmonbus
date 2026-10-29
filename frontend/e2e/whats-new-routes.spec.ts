import type { Page } from "@playwright/test";
import type { RouteListResponse } from "../src/shared/api/routeForecast.types";
import { apiPaths, observedAt, routeId } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { boardBackLink } from "./utils/verdict-board";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
});

const DAY = 24 * 60 * 60_000;
const ROUTE_1650 = "234000050";
const ROUTE_3330 = "204000057";
const mockRouteLabel = "3330번 노선, 새 노선, 테스트 기점부터 테스트 종점 구간";
const nextDayRouteLabel = "5600번 노선, 새 노선, 다른 기점부터 다른 종점 구간";

function whatsNewDialog(page: Page) {
  return page.getByRole("dialog", { name: "새 소식이 있어요", exact: true });
}

function newRoutesSection(page: Page) {
  return whatsNewDialog(page).getByRole("region", { name: "새로 추가된 노선", exact: true });
}

// 새 소식 기능 전부터 쓰던 기기는 NEW 기록만 있고 새 소식 기록은 없다. 다시 불러와도 덮어쓰지 않게 비어 있을 때만 넣는다.
async function visitAsReturningDevice(page: Page, knownRouteIds: readonly string[]) {
  await page.addInitScript((ids) => {
    if (localStorage.getItem("salmonbus.routeFirstSeen") !== null) return;
    localStorage.setItem("salmonbus.routeFirstSeen", JSON.stringify(Object.fromEntries(ids.map((id) => [id, 0]))));
  }, knownRouteIds);
}

// 새 소식 기능을 이미 쓰던 기기는 어제 만든 새 소식 기록도 있어서, 그 뒤에 처음 본 노선이 새 노선 칸에 나온다.
async function visitAsWhatsNewDevice(page: Page, knownRouteIds: readonly string[]) {
  await visitAsReturningDevice(page, knownRouteIds);
  const record = { lastSeenId: 0, lastShownDay: null, routesSeenUntil: new Date(observedAt).getTime() - DAY };
  await page.addInitScript((stored) => {
    if (localStorage.getItem("salmonbus.whatsNew") === null) localStorage.setItem("salmonbus.whatsNew", stored);
  }, JSON.stringify(record));
}

function recordedRouteIdsOf(page: Page) {
  return page.evaluate(() => Object.keys(JSON.parse(localStorage.getItem("salmonbus.routeFirstSeen") ?? "{}")));
}

function storedWhatsNewOf(page: Page) {
  return page.evaluate((): unknown => JSON.parse(localStorage.getItem("salmonbus.whatsNew") ?? "null"));
}

function waitForRoutes(page: Page) {
  return page.waitForResponse((response) => new URL(response.url()).pathname === apiPaths.routes);
}

async function reopenNextDayWithNewRoute(page: Page, routes: RouteListResponse["routes"]) {
  routes.push({
    id: "200000002",
    displayName: "5600",
    startStopName: "다른 기점",
    endStopName: "다른 종점",
    status: "FORECAST_READY",
  });
  await page.clock.setSystemTime(new Date(observedAt).getTime() + DAY);
  await page.reload();
}

async function expectOnlyNextDayRoute(page: Page) {
  await expect(newRoutesSection(page).getByRole("button")).toHaveCount(1);
  await expect(newRoutesSection(page).getByRole("button", { name: nextDayRouteLabel, exact: true })).toBeVisible();
}

test("새 소식 기능을 쓰던 기기에는 새 소식이 없어도 그 뒤에 서비스 중이 된 노선을 모달로 보여 준다", async ({
  page,
  api,
}) => {
  api.data.routes.routes.push(
    { id: ROUTE_1650, displayName: "1650", startStopName: "기점", endStopName: "종점", status: "FORECAST_READY" },
    { id: "200000002", displayName: "5600", startStopName: "기점", endStopName: "종점", status: "PREPARING" },
  );
  await visitAsWhatsNewDevice(page, [ROUTE_1650, ROUTE_3330]);

  await page.goto("/");

  await expect(newRoutesSection(page).getByRole("button")).toHaveCount(1);
  await expect(newRoutesSection(page).getByRole("button", { name: mockRouteLabel, exact: true })).toContainText("NEW");
  await expect(whatsNewDialog(page).getByText("새로 추가된 기능", { exact: true })).toHaveCount(0);
});

test("모달의 노선 줄을 누르면 그 노선의 판정 보드로 가고, 다음 날에는 그 뒤에 추가된 노선만 보여 준다", async ({
  page,
  api,
}) => {
  await visitAsWhatsNewDevice(page, [ROUTE_1650, ROUTE_3330]);
  await page.goto("/");

  await newRoutesSection(page).getByRole("button", { name: mockRouteLabel, exact: true }).click();

  await expect(page).toHaveURL(`/routes/${routeId}`);
  // 주소는 판정 보드를 그리기 전에 바뀐다. 보드가 그려지기 전에 뒤로 가면 홈이 다시 마운트되지 않는다.
  await expect(boardBackLink(page)).toBeVisible();
  expect(await page.evaluate(() => history.state?.usr?.boardEntry)).toBe("whats_new");

  const homeRoutes = waitForRoutes(page);
  await page.goBack();
  await homeRoutes;
  await expect(page).toHaveURL("/");
  await expect(whatsNewDialog(page)).toHaveCount(0);

  await reopenNextDayWithNewRoute(page, api.data.routes.routes);

  await expectOnlyNextDayRoute(page);
});

const closeWays = [
  {
    way: "닫기 버튼",
    close: (page: Page) => whatsNewDialog(page).getByRole("button", { name: "닫기", exact: true }).click(),
  },
  { way: "Escape", close: (page: Page) => page.keyboard.press("Escape") },
  // 모달이 가운데에 떠서 왼쪽 위 모서리는 어두운 배경이다.
  {
    way: "바깥 누름",
    close: (page: Page, isMobile: boolean) => (isMobile ? page.touchscreen.tap(5, 5) : page.mouse.click(5, 5)),
  },
];

for (const { way, close } of closeWays) {
  test(`새 노선만 있는 모달은 다시 보지 않기 없이 닫히고, 닫은 노선은 다음 날 보여 주지 않는다: ${way}`, async ({
    page,
    api,
    isMobile,
  }) => {
    await visitAsWhatsNewDevice(page, [ROUTE_1650, ROUTE_3330]);
    await page.goto("/");
    await expect(newRoutesSection(page).getByRole("button", { name: mockRouteLabel, exact: true })).toBeVisible();
    await expect(whatsNewDialog(page).getByRole("button", { name: "다시 보지 않기" })).toHaveCount(0);

    await close(page, isMobile);

    await expect(whatsNewDialog(page)).toHaveCount(0);
    await reopenNextDayWithNewRoute(page, api.data.routes.routes);
    await expectOnlyNextDayRoute(page);
  });
}

test("처음 온 기기에는 띄우지 않고, 다음 날 그 뒤에 추가된 노선만 보여 준다", async ({ page, api }) => {
  api.data.routes.routes.push({
    id: ROUTE_1650,
    displayName: "1650",
    startStopName: "기점",
    endStopName: "종점",
    status: "FORECAST_READY",
  });
  await page.goto("/");

  await expect.poll(() => recordedRouteIdsOf(page)).toContain(routeId);
  expect(await storedWhatsNewOf(page)).toMatchObject({ lastShownDay: null });
  await expect(whatsNewDialog(page)).toHaveCount(0);

  await reopenNextDayWithNewRoute(page, api.data.routes.routes);

  await expectOnlyNextDayRoute(page);
});

test("새 소식 기능 전부터 쓰던 기기는 첫 진입에 지금 NEW인 노선을 띄우지 않고, 다음 날 그 뒤에 추가된 노선만 보여 준다", async ({
  page,
  api,
}) => {
  await visitAsReturningDevice(page, [ROUTE_1650, ROUTE_3330]);
  await page.goto("/");

  // 노선 목록을 적은 콜백이 띄울지도 정한다. mock 3330은 이 진입에 처음 적혀 검색 화면에서는 NEW다.
  await expect.poll(() => recordedRouteIdsOf(page)).toContain(routeId);
  expect(await storedWhatsNewOf(page)).toMatchObject({ lastSeenId: 0, lastShownDay: null });
  await expect(whatsNewDialog(page)).toHaveCount(0);

  await reopenNextDayWithNewRoute(page, api.data.routes.routes);

  await expectOnlyNextDayRoute(page);
});

test("새 노선이 5개보다 많으면 번호 순서로 5개만 보여 주고 남은 개수를 알린다", async ({ page, api }) => {
  api.data.routes.routes.push(
    ...["9300", "1500-2", "G8110", "5600", "3000", "7007"].map((displayName, index) => ({
      id: String(200000002 + index),
      displayName,
      startStopName: "기점",
      endStopName: "종점",
      status: "FORECAST_READY" as const,
    })),
  );
  await visitAsWhatsNewDevice(page, [ROUTE_1650, ROUTE_3330]);

  await page.goto("/");

  const rows = newRoutesSection(page).getByRole("button");
  await expect(rows).toHaveCount(5);
  const expectedLabels = [
    "1500-2번 노선, 새 노선, 기점부터 종점 구간",
    "3000번 노선, 새 노선, 기점부터 종점 구간",
    mockRouteLabel,
    "5600번 노선, 새 노선, 기점부터 종점 구간",
    "7007번 노선, 새 노선, 기점부터 종점 구간",
  ];
  for (const [index, label] of expectedLabels.entries()) {
    await expect(rows.nth(index)).toHaveAccessibleName(label);
  }
  await expect(newRoutesSection(page).getByText("노선 2개가 더 추가됐어요", { exact: true })).toBeVisible();
});
