import { apiPaths, routeId } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { routeButton, routeSearchInput, selectRoute } from "./utils/route-select";
import { boardBackLink } from "./utils/verdict-board";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

test("노선 조회 실패 후 재시도에 성공하면 노선을 선택할 수 있다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");

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

  await expect(status).toHaveText("");
  const boardResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.board && response.status() === 200,
  );
  const vehiclesResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
  );
  await selectRoute(page);
  for (const response of await Promise.all([boardResponse, vehiclesResponse])) {
    expect(await response.finished()).toBeNull();
  }
});

test("노선 재시도도 실패하면 실패 안내와 재시도 버튼이 표시된다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");

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
  const input = routeSearchInput(page);
  const status = page.getByRole("main").getByRole("status");

  await input.fill("33");
  await expect(routeButton(page)).toBeVisible();
  await expect(status).toHaveText("일치하는 노선 1개");

  await input.fill("7");
  await expect(routeButton(page)).toHaveCount(0);
  await expect(status).toHaveText("일치하는 노선이 없어요");
});

test("지우기 버튼을 누르면 입력과 결과가 사라지고 입력창에 포커스가 남는다", async ({ page }) => {
  await page.goto("/");
  const input = routeSearchInput(page);
  await input.fill("33");
  await expect(routeButton(page)).toBeVisible();

  await page.getByRole("button", { name: "검색어 지우기", exact: true }).click();

  await expect(input).toHaveValue("");
  await expect(input).toBeFocused();
  await expect(routeButton(page)).toHaveCount(0);
  await expect(page).toHaveURL("/");
});

test("결과가 하나일 때 엔터를 누르면 그 노선의 판정 보드로 이동한다", async ({ page }) => {
  await page.goto("/");
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
  await routeSearchInput(page).fill("3");
  await expect(page.getByRole("main").getByRole("status")).toHaveText("일치하는 노선 2개");

  await routeSearchInput(page).press("Enter");

  await expect(page).toHaveURL("/?q=3");
});

test("판정 보드에서 뒤로 오면 입력한 번호와 결과가 남아 있다", async ({ page }) => {
  await page.goto("/");
  await selectRoute(page);

  await page.goBack();

  await expect(page).toHaveURL("/?q=3330");
  await expect(routeSearchInput(page)).toHaveValue("3330");
  await expect(routeButton(page)).toBeVisible();
});

test("판정 보드 헤더의 뒤로 가기를 눌러도 입력한 번호와 결과가 남아 있다", async ({ page }) => {
  await page.goto("/");
  await selectRoute(page);
  const historyLength = await page.evaluate(() => history.length);

  await boardBackLink(page).click();

  await expect(page).toHaveURL("/?q=3330");
  await expect(routeSearchInput(page)).toHaveValue("3330");
  await expect(routeButton(page)).toBeVisible();
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
});

test("판정 보드로 바로 들어오면 헤더의 뒤로 가기가 노선 선택 화면으로 간다", async ({ page }) => {
  await page.goto(`/routes/${routeId}`);

  await boardBackLink(page).click();

  await expect(page).toHaveURL("/");
  await expect(routeSearchInput(page)).toHaveValue("");
});

test("노선 선택 화면에서만 html 배경을 주황으로 칠한다", async ({ page }) => {
  const htmlBackground = () => page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
  await page.goto("/");

  await expect.poll(htmlBackground).toBe("rgb(232, 82, 63)");

  await selectRoute(page);

  await expect.poll(htmlBackground).toBe("rgba(0, 0, 0, 0)");
});

test("번호를 한 글자씩 쳐도 방문 기록이 늘지 않는다", async ({ page }) => {
  await page.goto("/");
  const historyLength = await page.evaluate(() => history.length);

  await routeSearchInput(page).pressSequentially("3330");

  await expect(page).toHaveURL("/?q=3330");
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
});
