import { apiPaths } from "./fixtures/mock-data";
import { test, expect } from "./fixtures/api";
import { routeButton, selectRoute } from "./utils/route-select";
import { expectDirection } from "./utils/verdict-board";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

test("노선 조회 실패 후 재시도에 성공하면 노선을 선택할 수 있다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");

  const error = page.getByText("노선을 불러오지 못했어요", { exact: true });
  const retry = page.getByRole("button", { name: "다시 시도하기", exact: true });
  await expect(error).toBeVisible();
  await expect(retry).toBeVisible();
  await expect(routeButton(page)).toHaveCount(0);

  // 요청 횟수가 아니라 사용자 재시도 단계에 맞춰 응답을 변경한다.
  api.mode.routes = "success";
  const response = page.waitForResponse(
    (response) => new URL(response.url()).pathname === apiPaths.routes && response.status() === 200,
  );
  await retry.click();
  await response;

  await expect(error).not.toBeVisible();
  await expect(routeButton(page)).toBeVisible();
  await expect(routeButton(page)).toBeEnabled();
  await selectRoute(page);
});

test("노선 재시도도 실패하면 실패 안내와 재시도 버튼이 표시된다", async ({ page, api }) => {
  api.mode.routes = "network-error";
  await page.goto("/");

  const error = page.getByText("노선을 불러오지 못했어요", { exact: true });
  const retry = page.getByRole("button", { name: "다시 시도하기", exact: true });
  await expect(error).toBeVisible();
  await expect(retry).toBeVisible();

  const requestsBeforeRetry = api.requests.routes;
  const failedRequest = page.waitForEvent(
    "requestfailed",
    (request) => new URL(request.url()).pathname === apiPaths.routes,
  );
  await retry.click();
  await failedRequest;
  expect(api.requests.routes).toBeGreaterThan(requestsBeforeRetry);

  await expect(error).toBeVisible();
  await expect(retry).toBeVisible();
  await expect(retry).toBeEnabled();
  await expect(page.getByText("노선을 불러오는 중이에요", { exact: true })).not.toBeVisible();
  await expect(routeButton(page)).toHaveCount(0);
});
