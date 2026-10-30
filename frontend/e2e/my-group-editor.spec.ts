import { test, expect } from "./fixtures/favorites";

test.use({ viewport: { width: 320, height: 844 }, reducedMotion: "reduce" });
const schoolId = "10000000-0000-4000-8000-000000000003";
const weekendId = "10000000-0000-4000-8000-000000000004";

test("그룹 카드 편집은 순서·삭제 아이콘을 표시하고 실패 후 재시도하며 미저장 순서를 유지한다", async ({
  page,
  favorites,
}) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] }, { id: weekendId, name: "주말", favorites: [] });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 그룹 편집", exact: true });
  const list = editor.getByRole("list", { name: "그룹 순서", exact: true });
  await expect(list.getByRole("listitem")).toHaveCount(3);
  await expect(editor.getByRole("button", { name: "그룹 추가", exact: true })).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "즐겨찾기 그룹 삭제", exact: true })).toHaveCount(0);
  await list.getByRole("button", { name: "주말 순서 변경", exact: true }).press("ArrowUp");
  await list.getByRole("button", { name: "주말 순서 변경", exact: true }).press("ArrowUp");
  await expect(list.getByRole("listitem").first()).toContainText("주말");
  await expect(list.getByRole("listitem").first()).toContainText("대표 그룹");
  await expect(editor.getByRole("button", { name: "주말 그룹 삭제", exact: true })).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "즐겨찾기 그룹 삭제", exact: true })).toBeVisible();
  await editor.getByRole("button", { name: "통학 그룹 삭제", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "통학 그룹을 삭제할까요?", exact: true });
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  expect(favorites.groups).toHaveLength(3);
  await editor.getByRole("button", { name: "통학 그룹 삭제", exact: true }).click();
  favorites.mutationError = "INTERNAL_ERROR";
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  favorites.mutationError = null;
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(list.getByRole("listitem")).toHaveCount(2);
  await expect(editor.getByRole("link", { name: "새 그룹 만들기", exact: true })).toBeVisible();
  favorites.mutationError = "INTERNAL_ERROR";
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor.getByRole("alert")).toBeVisible();
  favorites.mutationError = null;
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.name)).toEqual(["주말", "즐겨찾기"]);
});

test("그룹 순서를 바꾼 뒤 Escape로 닫으면 저장하지 않는다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 그룹 편집", exact: true });
  await editor.getByRole("button", { name: "통학 순서 변경", exact: true }).press("ArrowUp");
  await page.keyboard.press("Escape");
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.name)).toEqual(["즐겨찾기", "통학"]);
  expect(favorites.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
});

test("새 대표 표시는 즉시 바뀌고 이전 대표 삭제는 순서 저장 후 실행하며 실패해도 재시도한다", async ({
  page,
  favorites,
  api,
}) => {
  const oldId = favorites.groups[0]!.id;
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  let failDelete = true;
  const mutations: string[] = [];
  await api.route(async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "PUT" && path.endsWith("/favorite-groups/order")) mutations.push("order");
    if (request.method() === "DELETE" && path.endsWith(oldId)) {
      mutations.push("delete");
      if (favorites.groups[0]?.id === oldId || failDelete) {
        await route.fulfill({
          status: 409,
          json: {
            code: favorites.groups[0]?.id === oldId ? "LAST_FAVORITE_GROUP" : "INTERNAL_ERROR",
            message: "failed",
            requestId: "group-representative-test",
          },
        });
        return;
      }
    }
    await route.fallback();
  });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 그룹 편집", exact: true });
  await editor.getByRole("button", { name: "통학 순서 변경", exact: true }).press("ArrowUp");
  await expect(editor.getByRole("listitem").first()).toContainText("통학 · 대표 그룹");
  await expect(editor.getByRole("button", { name: "통학 그룹 삭제", exact: true })).toHaveCount(0);
  expect(favorites.groups[0]!.id).toBe(oldId);
  await editor.getByRole("button", { name: "즐겨찾기 그룹 삭제", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "즐겨찾기 그룹을 삭제할까요?", exact: true });
  favorites.mutationError = "INTERNAL_ERROR";
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(dialog.getByRole("status")).toHaveCount(0);
  expect(mutations).toEqual(["order"]);
  expect(favorites.groups[0]!.id).toBe(oldId);
  favorites.mutationError = null;
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  expect(favorites.groups[0]!.id).toBe(schoolId);
  await expect(dialog.getByRole("status")).toHaveText(
    "일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요.",
  );
  failDelete = false;
  await expect(dialog.getByRole("button", { name: "삭제", exact: true })).toBeEnabled();
  await dialog.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(mutations).toEqual(["order", "order", "delete", "delete"]);
  await expect(
    editor
      .getByRole("status")
      .filter({ hasText: "일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요." }),
  ).toHaveCount(0);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.name)).toEqual(["통학"]);
});
