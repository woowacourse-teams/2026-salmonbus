import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 320, height: 844 }, reducedMotion: "reduce" });
const secondGroupId = "10000000-0000-4000-8000-000000000003";

test("홈 편집 중 그룹을 추가해도 미저장 그룹·정류장 순서를 유지하고 함께 저장한다", async ({
  page,
  favorites,
  api,
}) => {
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const groupList = editor.getByRole("list", { name: "그룹 순서", exact: true });
  await groupList.getByRole("button", { name: "통학 순서 변경", exact: true }).press("ArrowLeft");
  const stops = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true });
  await stops
    .getByRole("button", { name: /순서 변경$/ })
    .last()
    .press("ArrowUp");

  const add = editor.getByRole("button", { name: "그룹 추가", exact: true });
  await add.click();
  const dialog = page.getByRole("dialog", { name: "그룹 추가", exact: true });
  const input = dialog.getByRole("textbox", { name: "그룹 이름", exact: true });
  await input.fill("취소할 이름");
  await input.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(editor).toBeVisible();
  await expect(add).toBeFocused();
  await add.click();
  await input.fill("퇴근");
  await dialog.getByRole("button", { name: "만들기", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(groupList.getByRole("button", { name: "퇴근", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(groupList.getByRole("button", { name: "퇴근", exact: true })).toBeFocused();
  await expect(add).toHaveCount(0);
  const rows = groupList.getByRole("listitem");
  await expect(rows.nth(0).getByRole("button", { name: "통학", exact: true })).toBeVisible();
  await expect(rows.nth(1).getByRole("button", { name: "즐겨찾기", exact: true })).toBeVisible();
  await expect(rows.nth(2).getByRole("button", { name: "퇴근", exact: true })).toBeVisible();
  expect(favorites.groups.map((group) => group.name)).toEqual(["즐겨찾기", "통학", "퇴근"]);
  expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);

  await groupList.getByRole("button", { name: "즐겨찾기", exact: true }).click();
  await expect(stops.getByRole("listitem").first()).toContainText(second!.name);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.name)).toEqual(["통학", "즐겨찾기", "퇴근"]);
  expect(favorites.groups[1]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "퇴근", exact: true })).toBeVisible();
  expect(favorites.requests.filter((request) => request.method === "POST")).toHaveLength(1);
});

test("그룹 알약의 여백을 드래그해 순서를 바꾸고 Escape로 취소하며 짧게 눌러 선택한다", async ({ page, favorites }) => {
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const list = editor.getByRole("list", { name: "그룹 순서", exact: true });
  const first = list.getByRole("button", { name: "즐겨찾기", exact: true });
  const second = list.getByRole("button", { name: "통학", exact: true });
  await second.click();
  await expect(second).toHaveAttribute("aria-pressed", "true");
  await first.click();
  const row = list.getByRole("listitem").filter({ has: page.getByRole("button", { name: "통학", exact: true }) });
  const from = (await row.boundingBox())!;
  const to = (await first.boundingBox())!;
  const x = from.x + 4;
  const y = from.y + from.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(to.x - 10, y, { steps: 12 });
  await page.keyboard.press("Escape");
  await page.mouse.up();
  await expect(editor).toBeVisible();
  await expect(list.getByRole("listitem").first()).toContainText("즐겨찾기");
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(to.x - 10, y, { steps: 12 });
  await page.mouse.up();
  await expect(list.getByRole("listitem").first()).toContainText("통학");
  await expect(first).toHaveAttribute("aria-pressed", "true");
  expect(favorites.groups.map((group) => group.name)).toEqual(["즐겨찾기", "통학"]);
  await second.click();
  await expect(second).toHaveAttribute("aria-pressed", "true");
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.name)).toEqual(["통학", "즐겨찾기"]);
});
