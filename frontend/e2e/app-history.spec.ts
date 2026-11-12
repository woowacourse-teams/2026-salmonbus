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
  await test.step("하단 탭을 바꿔도 방문 기록이 늘지 않고 스와이프를 막는다", async () => {
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
      await expect(root).toHaveAttribute("data-history-swipe", "false");
    }
  });

  for (const [tab, tabPath, listName] of [
    ["노선도", "/route-map", "서비스 중 노선"],
    ["즐겨찾기", "/favorites", undefined],
  ] as const) {
    await test.step(`${tab}에서 연 판정보드는 스와이프를 허용하고 이전 탭으로 돌아간다`, async () => {
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
    });
  }

  await tabs.getByRole("link", { name: "마이페이지", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  for (const [label, detailPath] of [
    ["새 소식", "/mypage/whats-new"],
    ["닉네임 수정", "/mypage/nickname"],
  ] as const) {
    await test.step(`${label}에서 새 기록을 쌓지 않고 마이페이지로 돌아간다`, async () => {
      await page.getByRole("link", { name: label, exact: true }).click();
      await expect(page).toHaveURL(detailPath);
      await expect(root).toHaveAttribute("data-history-swipe", "true");
      await page.getByRole("link", { name: "마이페이지로 돌아가기" }).click();
      await expect(page).toHaveURL("/mypage");
      await expect(root).toHaveAttribute("data-history-swipe", "false");
      await page.goForward();
      await expect(page).toHaveURL(detailPath);
      await page.goBack();
      await expect(page).toHaveURL("/mypage");
    });
  }

  await test.step("새 소식을 직접 열어도 뒤로가기 버튼은 마이페이지로 보낸다", async () => {
    await page.goto("/mypage/whats-new");
    const historyLength = await page.evaluate(() => window.history.length);
    await page.getByRole("link", { name: "마이페이지로 돌아가기" }).click();
    await expect(page).toHaveURL("/mypage");
    expect(await page.evaluate(() => window.history.length)).toBe(historyLength);
  });
});
