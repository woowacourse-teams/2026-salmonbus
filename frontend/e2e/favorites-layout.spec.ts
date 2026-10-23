import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect, storedFavorite, defaultGroupId } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";

const test = mergeTests(mapTest, favoritesTest);

for (const width of [320, 425]) {
  test(`즐겨찾기 홈과 저장 창은 ${width}px 화면에 맞고 주요 내용을 가리지 않는다`, async ({
    page,
    favorites,
    api,
    browserName,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
    first!.name = "야탑역.종합버스터미널";
    api.data.watch.route.directions[0].terminalStopName = "안양역";
    second!.name = "안양역.시외버스터미널";
    favorites.groups[0]!.favorites = [
      storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
      storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
    ];
    favorites.groups.push({ id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] });
    await page.goto("/");
    await expect(page.getByRole("tabpanel").getByRole("link")).toHaveCount(2);
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await testInfo.attach("즐겨찾기 홈", { body: await page.screenshot(), contentType: "image/png" });
    if (browserName === "chromium") await page.screenshot({ path: test.info().outputPath(`home-${width}.png`) });
    if (width === 425 && browserName === "chromium") {
      await page.screenshot({ path: test.info().outputPath("home.png") });
      await page
        .getByRole("tabpanel")
        .getByRole("listitem")
        .first()
        .screenshot({ path: test.info().outputPath("home-card.png") });
    }

    await page.getByRole("link", { name: "마이페이지", exact: true }).click();
    await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리" }).getByRole("link")).toHaveCount(3);
    const groups = page.getByRole("region", { name: "즐겨찾기 그룹 관리" });
    const groupCard = groups.locator("ul a").first();
    const groupBounds = await groupCard.boundingBox();
    expect(groupBounds!.width).toBe(width === 425 ? 356 : width - 64);
    expect(groupBounds!.height).toBe(89);
    await expect(groupCard.getByText("2 / 2", { exact: true })).toHaveCSS("background-color", "rgb(255, 240, 235)");
    expect((await groups.getByRole("link", { name: "새 그룹 만들기", exact: true }).boundingBox())!.height).toBe(50);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("groups.png") });
    await page.getByRole("link", { name: /즐겨찾기.*2 \/ 2/ }).click();
    await expect(page.getByRole("heading", { name: "즐겨찾기", exact: true })).toBeVisible();
    await expect(page.getByText(first!.name, { exact: false })).toBeVisible();
    const detailCard = page.locator("main li").first();
    const detailBounds = await detailCard.boundingBox();
    expect(detailBounds!.width).toBe(width === 425 ? 356 : width - 64);
    expect(detailBounds!.height).toBe(width === 425 ? 77 : 99);
    const groupDeleteBounds = (await page.getByRole("button", { name: "그룹 삭제", exact: true }).boundingBox())!;
    expect(groupDeleteBounds.x + groupDeleteBounds.width).toBeCloseTo(detailBounds!.x + detailBounds!.width - 8, 1);
    await expect(page.getByRole("heading", { name: "즐겨찾기", exact: true })).toHaveCSS("font-size", "24px");
    const remove = detailCard.getByRole("button", { name: /삭제/ });
    expect((await remove.boundingBox())!.width).toBe(48);
    expect((await remove.boundingBox())!.height).toBe(32);
    await expect(page.getByRole("button", { name: "순서 편집", exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("group-detail.png") });

    await page.goto(`/routes/${routeId}`);
    await page.getByRole("button", { name: `${first!.name} 즐겨찾기`, exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("checkbox", { name: /즐겨찾기/ })).toBeChecked();
    const bounds = await dialog.boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(844);
    await testInfo.attach("즐겨찾기 저장 창", { body: await page.screenshot(), contentType: "image/png" });
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("selection.png") });
    await dialog.getByRole("button", { name: "취소", exact: true }).click();
    await page.goto("/");
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    await expect(editor).toBeVisible();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect(await editor.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("order.png") });
    expect(favorites.groups[0]!.id).toBe(defaultGroupId);
  });
}
