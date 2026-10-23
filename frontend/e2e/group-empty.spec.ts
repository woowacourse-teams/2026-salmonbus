import { test, expect } from "./fixtures/favorites";

for (const width of [320, 425]) {
  test(`${width}px 마이페이지의 빈 그룹 상세는 CnyGe 안내를 표시한다`, async ({ page, browserName }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/mypage");
    await page
      .getByRole("region", { name: "즐겨찾기 그룹 관리" })
      .getByRole("link", { name: /즐겨찾기.*0 \/ 2/ })
      .click();
    await expect(page.getByRole("heading", { name: "즐겨찾기", exact: true })).toBeVisible();
    const empty = page.getByText("아직 등록한 정류장이 없어요", { exact: true }).locator("..");
    await expect(empty).toBeVisible();
    await expect(empty.getByText("0 / 2", { exact: true })).toHaveCSS("color", "rgb(232, 83, 63)");
    await expect(empty).toHaveCSS("padding-top", "36px");
    await expect(empty).toHaveCSS("padding-bottom", "36px");
    await expect(empty).toHaveCSS("gap", "8px");
    await expect(empty).toHaveCSS("text-align", "center");
    await expect(page.getByText("아직 등록한 즐겨찾기가 없어요.", { exact: true })).toHaveCount(0);
    const remove = page.getByRole("button", { name: "그룹 삭제", exact: true });
    await expect(remove).toBeVisible();
    const emptyBounds = (await empty.boundingBox())!;
    const removeBounds = (await remove.boundingBox())!;
    expect(emptyBounds.width).toBe(width === 425 ? 356 : 256);
    expect(emptyBounds.height).toBeCloseTo(126.4, 0);
    expect(removeBounds.y - emptyBounds.y - emptyBounds.height).toBeCloseTo(32, 1);
    expect(removeBounds.x + removeBounds.width).toBeCloseTo(emptyBounds.x + emptyBounds.width - 8, 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-empty-detail.png") });
  });
}
