import { test, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ reducedMotion: "reduce" });

for (const width of [320, 425, 673]) {
  test(`${width}px에서 떠 있는 하단 탭으로 이동하고 마지막 콘텐츠를 가리지 않는다`, async ({ page, favorites }) => {
    favorites.groups.push(
      { id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] },
      { id: "10000000-0000-4000-8000-000000000004", name: "주말", favorites: [] },
    );
    await page.setViewportSize({ width, height: 988 });
    await page.goto("/");
    const nav = page.getByRole("navigation", { name: "주요 메뉴", exact: true });
    await expect(nav.getByRole("link", { name: "홈", exact: true })).toHaveAttribute("aria-current", "page");
    const frame = (await nav.boundingBox())!;
    expect(frame.x).toBeGreaterThan(0);
    expect(frame.x + frame.width).toBeLessThan(width);
    expect(frame.y + frame.height).toBeLessThan(988);
    for (const link of await nav.getByRole("link").all()) {
      const bounds = (await link.boundingBox())!;
      expect(bounds.width).toBeGreaterThanOrEqual(44);
      expect(bounds.height).toBeGreaterThanOrEqual(44);
    }
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: test.info().outputPath(`glass-home-${width}.png`) });
    await nav.getByRole("link", { name: "마이페이지", exact: true }).click();
    await expect(page).toHaveURL("/mypage");
    await expect(nav.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true })).toBeVisible();
    await page.screenshot({ path: test.info().outputPath(`glass-mypage-${width}.png`) });
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const privacy = (await page.getByRole("region", { name: "개인정보", exact: true }).boundingBox())!;
    const after = (await nav.boundingBox())!;
    expect(privacy.y + privacy.height).toBeLessThan(after.y);
    expect(after.y).toBeCloseTo(frame.y, 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: test.info().outputPath(`glass-mypage-bottom-${width}.png`) });
  });
}

test("계정 하위 화면에는 탭을 유지하고 검색·판정 보드에는 표시하지 않는다", async ({ page, auth }) => {
  const nav = page.getByRole("navigation", { name: "주요 메뉴", exact: true });
  await page.goto("/mypage/nickname");
  await expect(nav.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
  for (const path of ["/search", `/routes/${routeId}`]) {
    await page.goto(path);
    await expect(page.getByRole("main")).toBeVisible();
    await expect(nav).toHaveCount(0);
  }
  auth.member = null;
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
  await expect(nav).toBeVisible();
  await expect(nav.locator('[aria-current="page"]')).toHaveCount(0);
});
