import type { Page } from "@playwright/test";
import { apiPaths, routeId } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import {
  finishSearchNavigation,
  inServiceSection,
  openRouteSearch,
  pickRoute,
  routeButton,
  routeSearchBackLink,
  routeSearchEntry,
  routeSearchInput,
  selectRoute,
} from "./utils/route-select";
import { boardBackLink } from "./utils/verdict-board";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
});

test("홈에서 검색창을 누르면 검색 화면으로 이동하고 입력창에 포커스가 간다", async ({ page }) => {
  await page.goto("/");

  await openRouteSearch(page);

  await expect(routeSearchInput(page)).toBeFocused();
  await expect(routeSearchInput(page)).toHaveValue("");
});

test("키보드로 홈 검색바에 들어와 Enter를 누르면 검색 화면으로 이동한다", async ({ page }) => {
  await page.goto("/");
  await routeSearchEntry(page).focus();

  await routeSearchEntry(page).press("Enter");
  await finishSearchNavigation(page);

  await expect(routeSearchInput(page)).toBeFocused();
});

test("키보드로 홈 검색바에 들어와 바로 입력하면 친 글자가 검색 화면 입력창에 남는다", async ({ page }) => {
  await page.goto("/");
  await routeSearchEntry(page).focus();

  await routeSearchEntry(page).press("3");
  await finishSearchNavigation(page);

  await expect(routeSearchInput(page)).toBeFocused();
  await expect(routeSearchInput(page)).toHaveValue("3");
});

test("노선 조회 실패 후 재시도에 성공하면 노선을 선택할 수 있다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");
  await openRouteSearch(page);

  const status = page.getByRole("main").getByRole("status");
  const retry = page.getByRole("button", { name: "다시 시도하기", exact: true });
  await expect(status).toHaveText("노선을 불러오지 못했어요");
  await expect(retry).toBeVisible();
  await expect(routeButton(page)).toHaveCount(0);

  // 요청 횟수가 아니라 사용자 재시도 단계에 맞춰 응답을 변경한다.
  api.mode.routes = "success";
  const response = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.routes && response.status() === 200,
  );
  await retry.click();
  await response;

  await expect(status).toHaveText("서비스 중 노선 1개");
  const watchResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.watch && response.status() === 200,
  );
  await pickRoute(page);
  expect(await (await watchResponse).finished()).toBeNull();
});

test("노선 재시도도 실패하면 실패 안내와 재시도 버튼이 표시된다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");
  await openRouteSearch(page);

  const status = page.getByRole("main").getByRole("status");
  const retry = page.getByRole("button", { name: "다시 시도하기", exact: true });
  await expect(status).toHaveText("노선을 불러오지 못했어요");
  await expect(retry).toBeVisible();

  const requestsBeforeRetry = api.requests.routes;
  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.routes,
  );
  await retry.click();
  await failedRequest;
  expect(api.requests.routes).toBeGreaterThan(requestsBeforeRetry);

  await expect(status).toHaveText("노선을 불러오지 못했어요");
  await expect(retry).toBeVisible();
  await expect(retry).toBeEnabled();
  await expect(page.getByText("노선을 불러오는 중이에요", { exact: true })).not.toBeVisible();
  await expect(routeButton(page)).toHaveCount(0);
});

test("번호를 입력하면 일치하는 노선이 보이고, 일치하는 노선이 없으면 결과 없음을 알린다", async ({ page }) => {
  await page.goto("/");
  await openRouteSearch(page);
  const input = routeSearchInput(page);
  const status = page.getByRole("main").getByRole("status");

  await input.fill("33");
  await expect(routeButton(page)).toBeVisible();
  await expect(status).toHaveText("일치하는 노선 1개");

  await input.fill("7");
  await expect(routeButton(page)).toHaveCount(0);
  await expect(status).toHaveText("일치하는 노선이 없어요");
});

test("지우기 버튼을 누르면 입력이 사라지고 서비스 중 노선 전체가 다시 보이며 입력창에 포커스가 남는다", async ({
  page,
  api,
}) => {
  api.data.routes.routes.push({
    id: "200000002",
    displayName: "5600",
    startStopName: "다른 기점",
    endStopName: "다른 종점",
    status: "FORECAST_READY",
  });
  await page.goto("/");
  await openRouteSearch(page);
  const input = routeSearchInput(page);
  await input.fill("33");
  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^3330/]);

  await page.getByRole("button", { name: "검색어 지우기", exact: true }).click();

  await expect(input).toHaveValue("");
  await expect(input).toBeFocused();
  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^3330/, /^5600/]);
});

test("입력 전에는 서비스 중 노선 전체를 번호 오름차순으로 NEW 배지와 함께 보여 준다", async ({ page, api }) => {
  api.data.routes.routes.push(
    { id: "200000002", displayName: "G8110", startStopName: "기점", endStopName: "종점", status: "FORECAST_READY" },
    { id: "200000003", displayName: "1500-2", startStopName: "기점", endStopName: "종점", status: "FORECAST_READY" },
    { id: "200000004", displayName: "9300", startStopName: "기점", endStopName: "종점", status: "FORECAST_READY" },
    { id: "200000005", displayName: "1650", startStopName: "기점", endStopName: "종점", status: "PREPARING" },
  );
  await page.goto("/");
  await openRouteSearch(page);

  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^1500-2/, /^3330/, /^G8110/, /^9300/]);
  await expect(page.getByRole("main").getByRole("status")).toHaveText("서비스 중 노선 4개");
  await expect(
    page.getByRole("button", { name: "G8110번 노선, 새 노선, 기점부터 종점 구간", exact: true }),
  ).toContainText("NEW");
});

test("서비스 중 노선이 하나도 없으면 입력 전에 볼 수 있는 노선이 없다고 알린다", async ({ page, api }) => {
  api.data.routes.routes = api.data.routes.routes.map((route) => ({ ...route, status: "PREPARING" }));
  await page.goto("/");
  await openRouteSearch(page);

  await expect(page.getByText("지금 볼 수 있는 노선이 없어요", { exact: true })).toBeVisible();
  await expect(page.getByText("잠시 뒤에 다시 불러와 주세요.", { exact: true })).toBeVisible();
  await expect(page.getByRole("main").getByRole("status")).toHaveText(
    "지금 볼 수 있는 노선이 없어요. 잠시 뒤에 다시 불러와 주세요.",
  );
  await expect(page.getByRole("button", { name: "다시 불러오기", exact: true })).toBeVisible();
  await expect(inServiceSection(page)).toHaveCount(0);
});

test("서비스 중 노선이 없을 때 다시 불러오기를 누르면 서비스 중 노선이 보이고 입력창에 포커스가 남는다", async ({
  page,
  api,
}) => {
  api.data.routes.routes = api.data.routes.routes.map((route) => ({ ...route, status: "PREPARING" }));
  await page.goto("/");
  await openRouteSearch(page);
  const reload = page.getByRole("button", { name: "다시 불러오기", exact: true });
  await expect(reload).toBeVisible();

  api.data.routes.routes = api.data.routes.routes.map((route) => ({ ...route, status: "FORECAST_READY" }));
  const response = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.routes && response.status() === 200,
  );
  await reload.click();
  await response;

  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^3330/]);
  await expect(routeSearchInput(page)).toBeFocused();
});

test("다시 불러와도 서비스 중 노선이 없으면 같은 안내와 다시 불러오기 버튼이 다시 보인다", async ({ page, api }) => {
  api.data.routes.routes = api.data.routes.routes.map((route) => ({ ...route, status: "PREPARING" }));
  await page.goto("/");
  await openRouteSearch(page);
  const reload = page.getByRole("button", { name: "다시 불러오기", exact: true });
  await expect(reload).toBeVisible();

  const requestsBeforeReload = api.requests.routes;
  const response = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.routes && response.status() === 200,
  );
  await reload.click();
  await response;

  expect(api.requests.routes).toBeGreaterThan(requestsBeforeReload);
  await expect(page.getByRole("main").getByRole("status")).toHaveText(
    "지금 볼 수 있는 노선이 없어요. 잠시 뒤에 다시 불러와 주세요.",
  );
  await expect(reload).toBeVisible();
  await expect(reload).toBeEnabled();
});

test("입력 전 목록에서 노선을 누르면 그 노선의 판정 보드로 이동한다", async ({ page }) => {
  await page.goto("/");
  await openRouteSearch(page);

  await routeButton(page).click();

  await expect(page).toHaveURL(`/routes/${routeId}`);
});

async function pressRouteButton(page: Page) {
  const box = await routeButton(page).boundingBox();
  expect(box).not.toBeNull();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.mouse.down();
}

function pressStyleOf(page: Page) {
  return routeButton(page).evaluate((element) => {
    const { scale, backgroundColor } = getComputedStyle(element);
    return { scale, backgroundColor };
  });
}

test("노선 줄을 누르는 동안 줄이 살짝 작아지고 배경이 깔린다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  await openRouteSearch(page);

  await pressRouteButton(page);

  await expect.poll(() => pressStyleOf(page)).toEqual({ scale: "0.98", backgroundColor: "rgb(242, 244, 246)" });
});

// 데스크톱 프로젝트는 마우스가 줄 위에 있어 hover 배경도 같은 색이라, :active 배경은 hover가 없는 iphone-15에서 확인된다.
// 동작 줄이기면 전환 없이 바로 바뀌어야 해서 기다리지 않고 한 번만 읽는다.
test("동작 줄이기 설정이면 노선 줄을 누르는 동안 크기는 그대로고 배경만 깔린다", async ({ page }) => {
  await page.goto("/");
  await openRouteSearch(page);

  await pressRouteButton(page);

  expect(await pressStyleOf(page)).toEqual({ scale: "none", backgroundColor: "rgb(242, 244, 246)" });
});

test("입력하면 일치하는 노선을 서비스 중 노선 아래에 보여 준다", async ({ page }) => {
  await page.goto("/");
  await openRouteSearch(page);

  await routeSearchInput(page).fill("33");

  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^3330/]);
  await expect(routeButton(page)).toContainText("테스트 기점 ↔ 테스트 종점");
});

test("입력하면 일치하는 노선을 응답 순서가 아니라 번호 오름차순으로 보여 준다", async ({ page, api }) => {
  api.data.routes.routes.push({
    id: "200000002",
    displayName: "3000",
    startStopName: "다른 기점",
    endStopName: "다른 종점",
    status: "FORECAST_READY",
  });
  await page.goto("/");
  await openRouteSearch(page);

  await routeSearchInput(page).fill("3");

  await expect(inServiceSection(page).getByRole("button")).toHaveText([/^3000/, /^3330/]);
});

test("새로 생긴 서비스 중 노선은 다음 방문에 NEW로 보인다", async ({ page, api }) => {
  const afterLaunchDeadline = new Date("2027-01-10T08:00:00+09:00");
  await page.clock.setSystemTime(afterLaunchDeadline);
  api.cacheMaxAgeSeconds = 0;
  await page.goto("/");
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3");
  await expect(page.getByRole("main").getByRole("status")).toHaveText("일치하는 노선 1개");

  api.data.routes.routes.push({
    id: "200000002",
    displayName: "3000",
    startStopName: "새 기점",
    endStopName: "새 종점",
    status: "FORECAST_READY",
  });
  await routeSearchBackLink(page).click();
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3");

  await expect(
    page.getByRole("button", { name: "3000번 노선, 새 노선, 새 기점부터 새 종점 구간", exact: true }),
  ).toContainText("NEW");
});

test("검색 화면을 연 채 7일이 지나면 검색어를 바꿀 때 NEW가 사라진다", async ({ page, api }) => {
  await page.clock.setSystemTime(new Date("2027-01-10T08:00:00+09:00"));
  api.cacheMaxAgeSeconds = 0;
  await page.goto("/");
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3");
  await expect(page.getByRole("main").getByRole("status")).toHaveText("일치하는 노선 1개");

  api.data.routes.routes.push({
    id: "200000002",
    displayName: "3000",
    startStopName: "새 기점",
    endStopName: "새 종점",
    status: "FORECAST_READY",
  });
  await routeSearchBackLink(page).click();
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3");
  await expect(
    page.getByRole("button", { name: "3000번 노선, 새 노선, 새 기점부터 새 종점 구간", exact: true }),
  ).toContainText("NEW");

  await page.clock.setSystemTime(new Date("2027-01-18T08:00:00+09:00"));
  await routeSearchInput(page).fill("30");

  const expiredRoute = page.getByRole("button", { name: "3000번 노선, 새 기점부터 새 종점 구간", exact: true });
  await expect(expiredRoute).toBeVisible();
  await expect(expiredRoute).not.toContainText("NEW");
});

test("출시 기간에 처음 온 기기는 1650번과 3330번을 뺀 서비스 중 노선에 NEW를 본다", async ({ page, api }) => {
  api.data.routes.routes.push(
    {
      id: "234000050",
      displayName: "1650",
      startStopName: "테스트 기점",
      endStopName: "테스트 종점",
      status: "FORECAST_READY",
    },
    {
      id: "200000004",
      displayName: "1600",
      startStopName: "테스트 기점",
      endStopName: "테스트 종점",
      status: "FORECAST_READY",
    },
  );
  await page.goto("/");
  await openRouteSearch(page);
  await routeSearchInput(page).fill("16");

  const launchKnownRoute = page.getByRole("button", {
    name: "1650번 노선, 테스트 기점부터 테스트 종점 구간",
    exact: true,
  });
  await expect(launchKnownRoute).toBeVisible();
  await expect(launchKnownRoute).not.toContainText("NEW");
  await expect(
    page.getByRole("button", { name: "1600번 노선, 새 노선, 테스트 기점부터 테스트 종점 구간", exact: true }),
  ).toContainText("NEW");
});

test("결과가 하나일 때 엔터를 누르면 그 노선의 판정 보드로 이동한다", async ({ page }) => {
  await page.goto("/");
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3330");
  await expect(page.getByRole("main").getByRole("status")).toHaveText("일치하는 노선 1개");
  await routeSearchInput(page).press("Enter");

  await expect(page).toHaveURL(`/routes/${routeId}`);
});

test("결과가 여러 개면 엔터를 눌러도 이동하지 않는다", async ({ page, api }) => {
  api.data.routes.routes.push({
    id: "200000002",
    displayName: "3000",
    startStopName: "다른 기점",
    endStopName: "다른 종점",
    status: "FORECAST_READY",
  });
  await page.goto("/");
  await openRouteSearch(page);
  await routeSearchInput(page).fill("3");
  await expect(page.getByRole("main").getByRole("status")).toHaveText("일치하는 노선 2개");

  await routeSearchInput(page).press("Enter");

  await expect(page).toHaveURL("/search");
});

test("검색 화면에서 뒤로 가기를 누르면 홈으로 가고 방문 기록이 늘지 않는다", async ({ page }) => {
  await page.goto("/");
  const historyLength = await page.evaluate(() => history.length);
  await openRouteSearch(page);
  await routeSearchInput(page).fill("33");

  await routeSearchBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
  expect(await page.evaluate(() => history.length)).toBe(historyLength + 1);
});

test("검색 화면으로 바로 들어오면 뒤로 가기가 홈으로 간다", async ({ page }) => {
  await page.goto("/search");

  await routeSearchBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
});

test("검색 화면으로 바로 들어와 노선을 고르면 판정 보드의 뒤로 가기가 홈으로 간다", async ({ page }) => {
  await page.goto("/search");
  await pickRoute(page);

  await boardBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
});

test("검색 화면으로 바로 들어와 노선을 고르면 판정 보드에서 브라우저 뒤로 가기가 홈으로 간다", async ({ page }) => {
  await page.goto("/search");
  await pickRoute(page);

  await page.goBack();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
});

test("판정 보드에서 브라우저 뒤로 가기를 누르면 검색 화면을 거치지 않고 홈으로 간다", async ({ page }) => {
  await page.goto("/");
  await selectRoute(page);

  await page.goBack();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
});

test("판정 보드 헤더의 뒤로 가기를 누르면 홈으로 가고 방문 기록이 늘지 않는다", async ({ page }) => {
  await page.goto("/");
  await selectRoute(page);
  const historyLength = await page.evaluate(() => history.length);

  await boardBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
});

test("판정 보드로 바로 들어오면 헤더의 뒤로 가기가 노선 선택 화면으로 간다", async ({ page }) => {
  await page.goto(`/routes/${routeId}`);

  await boardBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchEntry(page)).toBeVisible();
});

test("홈에서만 html 배경을 주황으로 칠한다", async ({ page }) => {
  const htmlBackground = () => page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
  await page.goto("/");

  await expect.poll(htmlBackground).toBe("rgb(232, 82, 63)");

  await openRouteSearch(page);

  await expect.poll(htmlBackground).toBe("rgba(0, 0, 0, 0)");
});
