import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect, storedFavorite, defaultGroupId } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";

const test = mergeTests(mapTest, favoritesTest);

test("겹치는 두 정류장명은 차이를 남기고 하나를 삭제하면 끝 말줄임으로 돌아간다", async ({ page, favorites, api }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  first!.name = "더샵지제역센트럴파크1.2단지(중)";
  second!.name = "더샵지제역센트럴파크2.3단지(중)";
  api.data.watch.route.displayName = "6801";
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  await page.goto("/");
  const labels = page.locator("[data-favorite-name]");
  await expect(labels).toHaveCount(2);
  await page.evaluate(() => document.fonts.ready);
  await expect(labels.first().locator('[aria-hidden="true"]')).toContainText("1.2단지(중)");
  await expect(labels.last().locator('[aria-hidden="true"]')).toContainText("2.3단지(중)");
  await page.setViewportSize({ width: 375, height: 844 });
  await expect(labels.locator('[aria-hidden="true"]')).toHaveCount(0);
  await page.setViewportSize({ width: 320, height: 844 });
  await expect(labels.locator('[aria-hidden="true"]')).toHaveCount(2);
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: `${second!.name} 삭제`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "즐겨찾기를 삭제할까요?", exact: true });
  await dialog.getByRole("button", { name: "삭제하기", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(labels).toHaveCount(1);
  await expect(labels.locator('[aria-hidden="true"]')).toHaveCount(0);
  await expect(labels).toHaveText(first!.name);
  expect(await labels.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
});

for (const width of [320, 425]) {
  test(`홈과 편집 카드의 방면은 ${width}px에서 단어 중간에 줄바꿈하지 않는다`, async ({ page, favorites, api }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
    const terminal = "서울대학교병원장례식장앞";
    api.data.watch.route.directions[0].terminalStopName = terminal;
    favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: "UP" })];
    await page.goto("/");
    await page.evaluate(() => document.fonts.ready);
    const suffixTops = (element: HTMLElement | SVGElement) => {
      const node = element.firstChild!;
      const length = node.textContent!.length;
      return [length - 2, length - 1].map((index) => {
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + 1);
        return range.getBoundingClientRect().top;
      });
    };
    const direction = page.getByRole("tabpanel").getByText(`${terminal} 방면`, { exact: true });
    await expect(direction).toBeVisible();
    const homeTops = await direction.evaluate(suffixTops);
    expect(homeTops[0]).toBe(homeTops[1]);
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    const editorDirection = page
      .getByRole("region", { name: "즐겨찾기 편집", exact: true })
      .getByText(`${terminal} 방면`, { exact: true });
    await expect(editorDirection).toBeVisible();
    const editorTops = await editorDirection.evaluate(suffixTops);
    expect(editorTops[0]).toBe(editorTops[1]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test(`정보가 없는 카드는 ${width}px에서 안내와 재시도 버튼을 표시한다`, async ({ page, favorites, api }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.boardingAllowed)!;
    const saved = storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction });
    favorites.groups[0]!.favorites = [saved];
    favorites.statuses[saved.id] = "ERROR";
    await page.goto("/");
    const panel = page.getByRole("tabpanel");
    const message = panel.getByText("정류장 정보를 불러오지 못했어요", { exact: true });
    const retry = panel.getByRole("button", { name: "다시 확인하기", exact: true });
    await expect(message).toBeVisible();
    await expect(retry).toBeVisible();
    await expect(panel.getByText("노선 정보 없음", { exact: true })).toHaveCount(0);
    await expect(panel.getByText("정류장 정보 없음", { exact: true })).toHaveCount(0);
    expect(await message.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    const retryBounds = (await retry.boundingBox())!;
    expect(retryBounds.x).toBeGreaterThanOrEqual(0);
    expect(retryBounds.x + retryBounds.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    favorites.statuses[saved.id] = "READY";
    await retry.click();
    await expect(panel.getByText(stop.name, { exact: true })).toBeVisible();
    await expect(message).toHaveCount(0);
  });

  test(`홈 즐겨찾기의 긴 정류장명은 ${width}px 화면에서 한 줄 말줄임하고 방면명은 모두 표시한다`, async ({
    page,
    favorites,
    api,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const [shortStop, stop] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
    shortStop!.name = "야탑역";
    stop!.name = "분당서울대학교병원.장례식장.헬스케어혁신파크";
    const terminal = "안양역.안양1번가.중앙시장.안양대교";
    api.data.watch.route.directions[0].terminalStopName = terminal;
    favorites.groups[0]!.favorites = [
      storedFavorite({ routeId, stopId: shortStop!.stopId, direction: "UP" }),
      storedFavorite({ routeId, stopId: stop!.stopId, direction: "UP" }, 5),
    ];
    await page.goto("/");
    const cards = page.getByRole("tabpanel").getByRole("link");
    await expect(cards).toHaveCount(2);
    const card = cards.nth(1);
    await page.evaluate(() => document.fonts.ready);
    const name = card.getByText(stop!.name, { exact: true });
    await expect(name).toBeVisible();
    await expect(name).toHaveCSS("text-overflow", "ellipsis");
    expect(await name.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
    const shortNameBounds = (await cards.first().getByText(shortStop!.name, { exact: true }).boundingBox())!;
    expect((await name.boundingBox())!.height).toBeCloseTo(shortNameBounds.height, 1);
    const direction = card.getByText(`${terminal} 방면`, { exact: true });
    await expect(direction).toBeVisible();
    expect(await direction.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

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
