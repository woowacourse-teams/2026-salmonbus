import type { Page } from "@playwright/test";
import { test, expect, defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 425, height: 844 }, reducedMotion: "reduce" });
const schoolId = "10000000-0000-4000-8000-000000000003";

async function dropGroup(page: Page, name: string) {
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const handle = editor
    .getByRole("list", { name: "그룹 순서", exact: true })
    .getByRole("button", { name: `${name} 순서 변경`, exact: true });
  const from = (await handle.boundingBox())!;
  const to = (await editor.locator("[data-group-drop-zone]").boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  const target = editor.getByRole("region", { name: "그룹 삭제 영역", exact: true });
  await expect(target).toBeVisible();
  await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeVisible();
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 16 });
  await page.mouse.up();
}

test("그룹을 정류장 영역에 놓으면 확인 창을 열고 취소·실패·재시도 후 실제로 삭제한다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await dropGroup(page, "통학");
  const dialog = page.getByRole("dialog", { name: "그룹을 삭제하시겠습니까?", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("통학 그룹");
  expect(favorites.requests.filter((request) => request.method === "DELETE")).toHaveLength(0);
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeVisible();
  await expect(editor.getByRole("button", { name: "통학", exact: true })).toBeVisible();
  await dropGroup(page, "통학");
  favorites.mutationError = "INTERNAL_ERROR";
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  expect(favorites.groups).toHaveLength(2);
  favorites.mutationError = null;
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "통학", exact: true })).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId]);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
});

test("현재 대표 그룹을 휴지통에 놓아도 삭제하지 않고 보호 안내를 표시한다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  await dropGroup(page, "즐겨찾기");
  const dialog = page.getByRole("dialog", { name: "대표 그룹은 삭제할 수 없어요", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "삭제", exact: true })).toHaveCount(0);
  await dialog.getByRole("button", { name: "확인", exact: true }).click();
  expect(favorites.requests.some((request) => request.method === "DELETE")).toBe(false);
  expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId, schoolId]);
});

test("드래그 중 Escape와 삭제 영역 밖에 놓기는 정류장 영역으로 돌아온다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const handle = editor.getByRole("button", { name: "통학 순서 변경", exact: true });
  const from = (await handle.boundingBox())!;
  const to = (await editor.locator("[data-group-drop-zone]").boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 12 });
  await page.keyboard.press("Escape");
  await page.mouse.up();
  await expect(editor).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeVisible();
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + from.width / 2, to.y - 10, { steps: 12 });
  await page.mouse.up();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeVisible();
  const done = (await editor.getByRole("button", { name: "완료", exact: true }).boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(done.x + done.width / 2, done.y + done.height / 2, { steps: 12 });
  await page.mouse.up();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(editor.getByText("아직 등록한 정류장이 없어요", { exact: true })).toBeVisible();
  expect(favorites.requests.some((request) => request.method === "DELETE")).toBe(false);
});

test("이전 대표 삭제 전에 새 순서를 저장하고 실패 후 재시도해도 살아남은 정류장 편집을 유지한다", async ({
  page,
  favorites,
  api,
}) => {
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups.push({
    id: schoolId,
    name: "통학",
    favorites: [
      storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }, 5),
      storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 6),
    ],
  });
  let failDelete = true;
  const mutations: string[] = [];
  await api.route(async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "PUT")
      mutations.push(path.endsWith("/favorite-groups/order") ? "group-order" : "favorite-order");
    if (request.method() === "DELETE" && path.endsWith(defaultGroupId)) {
      mutations.push("delete");
      if (favorites.groups[0]?.id === defaultGroupId || failDelete) {
        await route.fulfill({
          status: 409,
          json: {
            code: favorites.groups[0]?.id === defaultGroupId ? "LAST_FAVORITE_GROUP" : "INTERNAL_ERROR",
            message: "failed",
            requestId: "drop-delete-test",
          },
        });
        return;
      }
    }
    await route.fallback();
  });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: "통학", exact: true }).click();
  const stops = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true });
  await stops
    .getByRole("button", { name: /순서 변경$/ })
    .last()
    .press("ArrowUp");
  await editor.getByRole("button", { name: "통학 순서 변경", exact: true }).press("ArrowLeft");
  await dropGroup(page, "즐겨찾기");
  const dialog = page.getByRole("dialog", { name: "그룹을 삭제하시겠습니까?", exact: true });
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  expect(favorites.groups[0]!.id).toBe(schoolId);
  await expect(dialog.getByRole("status")).toHaveText(
    "일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요.",
  );
  failDelete = false;
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(stops.getByRole("listitem").first()).toContainText(second!.name);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(mutations).toEqual(["group-order", "delete", "delete", "favorite-order"]);
  expect(favorites.groups[0]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
});

test("터치·포인터로 휴지통에 놓아도 그룹 삭제 확인 창이 열린다", async ({ page, favorites, browserName }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  if (browserName !== "chromium") {
    await dropGroup(page, "통학");
  } else {
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    const from = (await editor.getByRole("button", { name: "통학 순서 변경", exact: true }).boundingBox())!;
    const to = (await editor.locator("[data-group-drop-zone]").boundingBox())!;
    const x = from.x + from.width / 2;
    const y = from.y + from.height / 2;
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: true });
    await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y }] });
    for (let step = 1; step <= 16; step++) {
      await cdp.send("Input.dispatchTouchEvent", {
        type: "touchMove",
        touchPoints: [
          { x: x + ((to.x + to.width / 2 - x) * step) / 16, y: y + ((to.y + to.height / 2 - y) * step) / 16 },
        ],
      });
    }
    await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    await cdp.detach();
  }
  await expect(page.getByRole("dialog", { name: "그룹을 삭제하시겠습니까?", exact: true })).toBeVisible();
  expect(favorites.requests.some((request) => request.method === "DELETE")).toBe(false);
});
