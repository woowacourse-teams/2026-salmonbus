import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test("앱은 탭 이동을 방문 기록에 추가하지 않고 판정보드와 새 소식에서 진입 화면으로 돌아간다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.watch.stops.find(({ boardingAllowed }) => boardingAllowed);
  if (stop === undefined) throw new Error("승차 가능한 정류장이 필요합니다");
  favorites.groups[0]?.favorites.push(storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction }, 2));
  await page.addInitScript(() => {
    window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities: ["navigation.setHistorySwipe"] };
    window.ReactNativeWebView = {
      postMessage(raw: string) {
        const message = JSON.parse(raw);
        if (message.type === "navigation.setHistorySwipe") {
          document.documentElement.dataset.historySwipe = String(message.payload.allowed);
        }
      },
    };
  });

  await page.goto("/");
  const root = page.locator("html");
  const tabs = page.getByRole("navigation", { name: "주요 메뉴" });
  const historyLength = await page.evaluate(() => window.history.length);
  await expect(root).toHaveAttribute("data-history-swipe", "false");

  for (const [tab, tabPath] of [
    ["노선도", "/route-map"],
    ["즐겨찾기", "/favorites"],
    ["마이페이지", "/mypage"],
    ["홈", "/"],
  ] as const) {
    await tabs.getByRole("link", { name: tab, exact: true }).click();
    await expect(page).toHaveURL(tabPath);
    expect(await page.evaluate(() => window.history.length)).toBe(historyLength);
  }

  for (const [tab, tabPath, listName] of [
    ["노선도", "/route-map", "서비스 중 노선"],
    ["즐겨찾기", "/favorites", undefined],
  ] as const) {
    await tabs.getByRole("link", { name: tab, exact: true }).click();
    await expect(page).toHaveURL(tabPath);
    await expect(root).toHaveAttribute("data-history-swipe", "false");

    const list = listName ? page.getByRole("list", { name: listName }) : page.getByRole("tabpanel");
    await list.getByRole("link", { name: /3330/ }).click();
    await expect(page).toHaveURL(new RegExp(`/routes/${routeId}`));
    await expect(root).toHaveAttribute("data-history-swipe", "true");
    await page.goBack();
    await expect(page).toHaveURL(tabPath);
    await expect(root).toHaveAttribute("data-history-swipe", "false");
  }

  await tabs.getByRole("link", { name: "마이페이지", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await page.getByRole("link", { name: "새 소식" }).click();
  await expect(page).toHaveURL("/mypage/whats-new");
  await expect(root).toHaveAttribute("data-history-swipe", "true");
  await page.getByRole("link", { name: "마이페이지로 돌아가기" }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(root).toHaveAttribute("data-history-swipe", "false");
  await page.goForward();
  await expect(page).toHaveURL("/mypage/whats-new");
});
