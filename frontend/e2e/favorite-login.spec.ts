import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";

const test = mergeTests(mapTest, favoritesTest);
test.use({ reducedMotion: "reduce" });

for (const width of [320, 425]) {
  test(`비회원 즐겨찾기 팝업은 ${width}px에서 닫기·로그인 복귀를 지원한다`, async ({
    page,
    auth,
    api,
    favorites,
    browserName,
  }, testInfo) => {
    auth.member = null;
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
    const destination = `/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`;
    await page.goto(destination);
    const star = page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true });
    await star.click();
    const dialog = page.getByRole("dialog", { name: "로그인하면 즐겨찾기를 등록할 수 있어요", exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("로그인을 하면 자주 타는 노선과 정류장을");
    await expect(dialog).toContainText("내 그룹에 모아 볼 수 있어요.");
    const login = dialog.getByRole("link", { name: "로그인 / 회원가입", exact: true });
    await expect(login).toHaveAttribute("href", `/login?${new URLSearchParams({ returnTo: destination })}`);
    await page.evaluate(() => document.fonts.ready);
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    const bounds = (await dialog.boundingBox())!;
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(844);
    await testInfo.attach("비회원 즐겨찾기 로그인 안내", { body: await page.screenshot(), contentType: "image/png" });
    if (browserName === "chromium" && width === 425) {
      await page.screenshot({ path: test.info().outputPath("favorite-login.png") });
      await dialog.screenshot({ path: test.info().outputPath("favorite-login-dialog.png") });
    }

    await dialog.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(star).toBeFocused();
    await star.click();
    await dialog.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(star).toBeFocused();
    expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);

    await star.click();
    await login.click();
    await page.getByRole("button", { name: "카카오로 계속하기", exact: true }).click();
    await expect(page).toHaveURL(destination);
    await expect(star).toHaveAttribute("aria-pressed", "false");
    expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);
  });
}
