import { mergeTests, type Page } from "@playwright/test";
import { test as favoritesTest, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";
import { test as mapTest } from "./fixtures/naver-map";
import { directionButton } from "./utils/verdict-board";
import type { AppCommandType } from "../src/shared/bridge/bridge.types";

declare global {
  interface Window {
    appMessageTypes: string[];
  }
}

const test = mergeTests(mapTest, favoritesTest);
test.use({ viewport: { width: 390, height: 844 } });

const HAPTIC_CAPABILITIES: AppCommandType[] = ["haptic.impact", "haptic.selection", "haptic.success", "haptic.error"];

/** 진동 4종만 받는 가짜 앱 안에서 연다. 앱이 받은 메시지는 type만 모은다. */
async function openInApp(page: Page, path: string) {
  await page.addInitScript((capabilities) => {
    window.appMessageTypes = [];
    window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
    window.ReactNativeWebView = {
      postMessage(raw: string) {
        window.appMessageTypes.push((JSON.parse(raw) as { type: string }).type);
      },
    };
  }, HAPTIC_CAPABILITIES);
  await page.goto(path);
}

function appMessageTypes(page: Page) {
  return page.evaluate(() => window.appMessageTypes);
}

test("방향이 바뀔 때만 selection 진동을 보내고 같은 방향을 다시 누르면 보내지 않는다", async ({
  page,
  api,
  naverMap,
}) => {
  const { stops, route } = api.data.watch;
  const upStop = stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed && stop.sequence > 1)!;
  const upTerminal = route.directions.find((direction) => direction.id === "UP")!.terminalStopName;
  const upPin = page.getByRole("button", {
    name: `${upStop.name} ${upTerminal} 방면 정류장 지도에서 선택`,
    exact: true,
  });
  await openInApp(page, `/routes/${routeId}?direction=UP`);
  await expect(directionButton(page, "UP")).toHaveAttribute("aria-pressed", "true");
  await page.clock.runFor(48);
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
  // 두 방향 정류장 핀은 확대해야 보인다.
  await naverMap.setZoom(15);
  await naverMap.panToStop(upStop.sequence);

  await directionButton(page, "UP").click();
  await upPin.click();
  await expect(upPin).toHaveAttribute("aria-pressed", "true");
  expect(await appMessageTypes(page)).toEqual([]);

  await directionButton(page, "DOWN").click();
  await expect(directionButton(page, "DOWN")).toHaveAttribute("aria-pressed", "true");
  expect(await appMessageTypes(page)).toEqual(["haptic.selection"]);

  await directionButton(page, "DOWN").click();
  expect(await appMessageTypes(page)).toEqual(["haptic.selection"]);

  await naverMap.panToStop(upStop.sequence);
  await upPin.click();
  await expect(directionButton(page, "UP")).toHaveAttribute("aria-pressed", "true");
  expect(await appMessageTypes(page)).toEqual(["haptic.selection", "haptic.selection"]);
});

test("저장할 그룹을 체크하면 selection, 즐겨찾기를 저장하면 success 진동을 보낸다", async ({
  page,
  api,
  favorites,
}) => {
  const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  await openInApp(page, `/routes/${routeId}?direction=UP`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(1);
  expect(await appMessageTypes(page)).toEqual(["haptic.selection", "haptic.success"]);
});

test("저장 응답이 오기 전에 화면을 떠나면 결과 진동을 보내지 않는다", async ({ page, api, favorites }) => {
  let finish!: () => void;
  const release = new Promise<void>((resolve) => {
    finish = resolve;
  });
  await page.route("**/api/me/favorite-selections", async (route) => {
    await release;
    await route.fallback();
  });
  const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  await openInApp(page, `/routes/${routeId}?direction=UP`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  const started = page.waitForRequest((request) => request.method() === "PUT");
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await started;
  // 라우터가 주소를 먼저 바꾸고 떠나는 화면을 잠깐 남겨 두는 순간을 만든다.
  await page.evaluate(() => history.pushState(null, "", "/"));
  finish();
  await expect(dialog).not.toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(1);
  expect(await appMessageTypes(page)).toEqual(["haptic.selection"]);
});
