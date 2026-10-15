import { test, expect } from "./fixtures/api";
import { routeSearchInput } from "./utils/route-select";

test.use({
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

for (const width of [390, 425]) {
  for (const insets of [
    { top: 0, bottom: 0 },
    { top: 59, bottom: 34 },
  ]) {
    test(`화면 너비 ${width}px, safe area ${insets.top}/${insets.bottom}px에서 검색창과 마지막 결과가 가려지지 않는다`, async ({
      page,
      context,
      browserName,
      api,
    }) => {
      test.skip(browserName !== "chromium", "safe area 값 지정은 Chromium CDP에서 지원한다");
      const height = 844;
      await page.setViewportSize({ width, height });
      const session = await context.newCDPSession(page);
      await session.send("Emulation.setSafeAreaInsetsOverride", { insets });
      api.data.routes.routes = Array.from({ length: 30 }, (_, index) => ({
        id: String(200000000 + index),
        displayName: String(3000 + index),
        startStopName: "테스트 기점",
        endStopName: "테스트 종점",
        status: "FORECAST_READY",
      }));
      await page.goto("/");

      const search = page.getByRole("search");
      const initialBox = await search.boundingBox();
      expect(initialBox).not.toBeNull();
      expect(initialBox!.y + initialBox!.height / 2).toBeCloseTo((insets.top + height - insets.bottom) / 2, 0);
      const logoBox = await page.getByRole("img", { name: "연어 버스 로고", exact: true }).boundingBox();
      expect(logoBox).not.toBeNull();
      expect(logoBox!.y).toBeGreaterThanOrEqual(insets.top);

      await routeSearchInput(page).fill("3");
      const results = page.getByRole("region", { name: "검색 결과", exact: true });
      await expect(results.getByRole("button")).toHaveCount(30);
      const searchBox = await search.boundingBox();
      expect(searchBox).not.toBeNull();
      expect(searchBox!.y).toBeGreaterThanOrEqual(logoBox!.y + logoBox!.height);
      const resultsBox = await results.boundingBox();
      expect(resultsBox).not.toBeNull();
      expect(resultsBox!.y + resultsBox!.height).toBeLessThanOrEqual(height - insets.bottom);

      await results.evaluate((element) => {
        element.scrollTop = element.scrollHeight;
      });
      const lastResult = results.getByRole("button").last();
      await expect(lastResult).toBeInViewport({ ratio: 1 });
      const lastBox = await lastResult.boundingBox();
      expect(lastBox).not.toBeNull();
      expect(lastBox!.y + lastBox!.height).toBeLessThanOrEqual(height - insets.bottom);
    });
  }
}
