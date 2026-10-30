import { test, expect } from "./fixtures/api";
import { inServiceSection, openRouteSearch, routeSearchEntry, routeSearchInput } from "./utils/route-select";

test.use({ reducedMotion: "reduce" });

const noInsets = { top: 0, bottom: 0 };
const notchInsets = { top: 59, bottom: 34 };

// 휴대폰 기본 너비는 두 safe area를, 큰 휴대폰은 노치 값만, 데스크톱은 safe area 없이만 본다.
for (const { width, height, insets } of [
  { width: 390, height: 844, insets: noInsets },
  { width: 390, height: 844, insets: notchInsets },
  { width: 440, height: 956, insets: notchInsets },
  { width: 1280, height: 844, insets: noInsets },
]) {
  test(`화면 ${width}x${height}px, safe area ${insets.top}/${insets.bottom}px에서 홈 검색창 위치와 검색 화면의 마지막 결과가 가려지지 않는다`, async ({
    page,
    context,
    browserName,
    api,
  }) => {
    test.skip(browserName !== "chromium", "safe area 값 지정은 Chromium CDP에서 지원한다");
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

    const entryBox = await routeSearchEntry(page).boundingBox();
    expect(entryBox).not.toBeNull();
    expect(entryBox!.y).toBeGreaterThanOrEqual(insets.top);
    expect(entryBox!.y + entryBox!.height).toBeLessThan(height / 2);
    const logoBox = await page
      .getByRole("link", { name: "연어버스 홈으로 이동", exact: true })
      .locator("img")
      .boundingBox();
    expect(logoBox).not.toBeNull();
    expect(logoBox!.y).toBeGreaterThanOrEqual(insets.top);

    await openRouteSearch(page);
    await routeSearchInput(page).fill("3");
    const results = inServiceSection(page);
    await expect(results.getByRole("button")).toHaveCount(30);
    const searchBox = await page.getByRole("search").boundingBox();
    expect(searchBox).not.toBeNull();
    expect(searchBox!.y).toBeGreaterThanOrEqual(insets.top);
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
