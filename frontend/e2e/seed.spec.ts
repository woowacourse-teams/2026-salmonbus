import { test, expect } from "./fixtures/api";
import { routeSearchEntry } from "./utils/route-select";

// Playwright Test Agents(planner, generator)는 이 테스트를 끝까지 돌린 페이지에서 시작한다.
test("API 목 위에서 홈을 열고 에이전트가 조작할 수 있게 시계를 다시 흐르게 한다", async ({ page }) => {
  await page.goto("/");
  await expect(routeSearchEntry(page)).toBeVisible();
  // 에이전트의 브라우저 도구는 동작이 끝나기를 페이지 안의 setTimeout으로 기다려서, 시계가 멈춰 있으면 응답하지 않는다.
  await page.clock.resume();
});
