import { test, expect, defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";
import { captureEnabled } from "./utils/capture";
import { checkAtBothWidths } from "./utils/widths";

const schoolId = "10000000-0000-4000-8000-000000000003";
const weekendId = "10000000-0000-4000-8000-000000000004";

test.beforeEach(({ favorites, api }) => {
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  favorites.groups[0]!.name = "출퇴근";
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  favorites.groups.push(
    { id: schoolId, name: "통학", favorites: [] },
    { id: weekendId, name: "주말 나들이", favorites: [] },
  );
});

test("320px 그룹 삭제 선택·취소·확인·완료는 시안대로 동작하고 대표 그룹을 유지한다", async ({
  page,
  favorites,
  browserName,
}) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/mypage");
  const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
  const remove = region.getByRole("button", { name: "그룹 삭제 선택", exact: true });
  const order = region.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true });
  await expect(remove).toBeEnabled();
  expect((await remove.boundingBox())!.x).toBeLessThan((await order.boundingBox())!.x);
  await remove.click();
  await expect(region.getByRole("link")).toHaveCount(0);
  await expect(region.getByRole("checkbox")).toHaveCount(2);
  await expect(region.getByText("대표 그룹", { exact: true })).toBeVisible();
  await expect(region.getByRole("checkbox", { name: /출퇴근/ })).toHaveCount(0);
  await expect(region.getByRole("button", { name: "선택한 그룹 0개 삭제", exact: true })).toBeDisabled();
  const school = region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true });
  const weekend = region.getByRole("checkbox", { name: "주말 나들이 삭제 선택", exact: true });
  await school.check();
  await weekend.check();
  await region.getByRole("button", { name: "취소", exact: true }).click();
  await expect(region.getByRole("link")).toHaveCount(3);
  await expect(remove).toBeFocused();
  expect(favorites.requests.filter((request) => request.method === "DELETE")).toHaveLength(0);
  await remove.click();
  await expect(school).not.toBeChecked();
  await school.check();
  await weekend.check();
  const selected = region.getByRole("button", { name: "선택한 그룹 2개 삭제", exact: true });
  if (captureEnabled && browserName === "chromium") {
    await page.setViewportSize({ width: 425, height: 844 });
    await page.screenshot({ path: test.info().outputPath("salmonbus-group-bulk-selection.png") });
    await page.setViewportSize({ width: 320, height: 844 });
  }
  await selected.click();
  const dialog = page.getByRole("dialog", { name: /선택한 그룹 2개를/ });
  await checkAtBothWidths(page, async (width) => {
    const box = await dialog.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width);
    if (captureEnabled && width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-group-bulk-dialog.png") });
  });
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(selected).toBeFocused();
  await expect(school).toBeChecked();
  await expect(weekend).toBeChecked();
  await selected.click();
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(region.getByRole("link")).toHaveCount(2);
  await expect(remove).toHaveCount(0);
  await expect(order).toBeFocused();
  expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId]);
  expect(favorites.groups[0]!.favorites).toHaveLength(1);
  await expect(page.getByText("그룹 2개를 삭제했어요", { exact: true })).toBeVisible();
  await checkAtBothWidths(page, async (width) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (captureEnabled && width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-group-bulk-complete.png") });
  });
});

for (const failedId of [schoolId, weekendId]) {
  test(`삭제가 ${failedId === schoolId ? "첫 번째" : "두 번째"}에서 실패하면 즉시 멈추고 남은 그룹만 재시도한다`, async ({
    page,
    favorites,
    api,
  }) => {
    let fail = true;
    const calls: string[] = [];
    await api.route(async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() === "DELETE" && path.startsWith("/api/me/favorite-groups/")) {
        calls.push(path.split("/").at(-1)!);
        if (fail && path.endsWith(failedId)) {
          await route.fulfill({
            status: 503,
            json: { code: "SERVICE_UNAVAILABLE", message: "try later", requestId: "e2e-favorites" },
          });
          return;
        }
      }
      await route.fallback();
    });
    await page.goto("/mypage");
    const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
    await region.getByRole("button", { name: "그룹 삭제 선택", exact: true }).click();
    await region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true }).check();
    await region.getByRole("checkbox", { name: "주말 나들이 삭제 선택", exact: true }).check();
    await region.getByRole("button", { name: "선택한 그룹 2개 삭제", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "삭제", exact: true }).click();
    await expect(dialog.getByRole("alert")).toBeVisible();
    expect(calls).toEqual(failedId === schoolId ? [schoolId] : [schoolId, weekendId]);
    if (failedId === weekendId) {
      await expect(dialog).toHaveAccessibleName(/선택한 그룹 1개를/);
      await expect(dialog.getByRole("alert")).toContainText("일부 그룹은 삭제됐어요");
      await expect(region.getByRole("checkbox")).toHaveCount(1);
      expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId, weekendId]);
    } else {
      expect(favorites.groups).toHaveLength(3);
    }
    fail = false;
    const retry = dialog.getByRole("button", { name: "삭제", exact: true });
    await expect(retry).toBeEnabled();
    await retry.click();
    await expect(dialog).toHaveCount(0);
    expect(calls).toEqual(failedId === schoolId ? [schoolId, schoolId, weekendId] : [schoolId, weekendId, weekendId]);
    expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId]);
    await expect(page.getByText("그룹 2개를 삭제했어요", { exact: true })).toBeVisible();
  });
}

test("삭제 중에는 취소와 중복 클릭을 막고 대표 그룹 하나만 남으면 삭제 버튼을 숨긴다", async ({
  page,
  favorites,
  api,
}) => {
  let release = () => {};
  const response = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    if (route.request().method() === "DELETE") await response;
    await route.fallback();
  });
  await page.goto("/mypage");
  const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
  await region.getByRole("button", { name: "그룹 삭제 선택", exact: true }).click();
  await region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true }).check();
  await region.getByRole("button", { name: "선택한 그룹 1개 삭제", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await test.step("삭제 중에는 취소, Escape, 중복 클릭을 막음", async () => {
    try {
      await dialog.getByRole("button", { name: "삭제", exact: true }).click();
      await expect(dialog.getByRole("button", { name: "삭제 중…", exact: true })).toBeDisabled();
      await expect(dialog.getByRole("button", { name: "취소", exact: true })).toBeDisabled();
      await page.keyboard.press("Escape");
      await expect(dialog).toBeVisible();
    } finally {
      release();
    }
    await expect(dialog).toHaveCount(0);
    expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId, weekendId]);
    await expect(region.getByRole("button", { name: "그룹 삭제 선택", exact: true })).toBeVisible();
  });
  await test.step("대표 그룹 하나만 남으면 삭제 버튼 숨김", async () => {
    favorites.groups = favorites.groups.slice(0, 1);
    await page.reload();
    await expect(region.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true })).toBeEnabled();
    await expect(region.getByRole("button", { name: "그룹 삭제 선택", exact: true })).toHaveCount(0);
  });
});

test("선택 중 대표 그룹이 바뀌면 삭제를 막고 다시 선택한 현재 비대표 그룹만 삭제한다", async ({ page, favorites }) => {
  await page.goto("/mypage");
  const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
  await region.getByRole("button", { name: "그룹 삭제 선택", exact: true }).click();
  await region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true }).check();
  await region.getByRole("checkbox", { name: "주말 나들이 삭제 선택", exact: true }).check();
  await region.getByRole("button", { name: "선택한 그룹 2개 삭제", exact: true }).click();
  const dialog = page.getByRole("dialog");
  favorites.groups = [favorites.groups[1]!, favorites.groups[0]!, favorites.groups[2]!];
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await expect(dialog.getByRole("alert")).toContainText("그룹 목록이 변경됐어요");
  await expect(dialog.getByRole("button", { name: "삭제", exact: true })).toBeDisabled();
  expect(favorites.requests.filter((request) => request.method === "DELETE")).toHaveLength(0);
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true })).toHaveCount(0);
  await region.getByRole("checkbox", { name: "주말 나들이 삭제 선택", exact: true }).uncheck();
  await region.getByRole("checkbox", { name: "출퇴근 삭제 선택", exact: true }).check();
  await region.getByRole("button", { name: "선택한 그룹 1개 삭제", exact: true }).click();
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([schoolId, weekendId]);
});
