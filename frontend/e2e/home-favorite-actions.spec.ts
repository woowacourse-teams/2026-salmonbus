import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";
import { routeSearchInput } from "./utils/route-select";

test.use({ viewport: { width: 425, height: 844 }, reducedMotion: "reduce" });

test("정류장이 1개면 카드 아래에서 검색을 열 수 있고 2개면 추가 버튼을 숨긴다", async ({ page, favorites, api }) => {
  await page.setViewportSize({ width: 390, height: 940 });
  const stops = api.data.watch.stops.filter(({ boardingAllowed }) => boardingAllowed).slice(0, 2);
  const saved = stops.map(({ stopId, direction }, index) => storedFavorite({ routeId, stopId, direction }, index + 2));
  favorites.groups[0]!.favorites = saved.slice(0, 1);
  favorites.groups.push({ id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: saved });
  await page.goto("/favorites");
  const panel = page.getByRole("tabpanel");
  const cards = panel.getByRole("list").first().getByRole("link");
  const groupTabs = page.getByRole("tablist", { name: "즐겨찾기 그룹", exact: true });
  const sheetBody = page.getByRole("region", { name: "선택한 탭 내용", exact: true });
  const isScrollable = () => sheetBody.evaluate((element) => element.scrollHeight > element.clientHeight + 1);
  const add = panel.getByRole("link", { name: "즐겨찾기 추가하기", exact: true });
  await expect(cards).toHaveCount(1);
  await expect(add).toBeVisible();
  expect(await isScrollable()).toBe(false);
  const tabsBefore = (await groupTabs.boundingBox())!;
  const firstCardBefore = (await cards.first().boundingBox())!;
  const lastCard = (await cards.last().boundingBox())!;
  expect((await add.boundingBox())!.y).toBeGreaterThan(lastCard.y + lastCard.height);
  await page.getByRole("tab", { name: "통학", exact: true }).click();
  await expect(cards).toHaveCount(2);
  await expect(add).toHaveCount(0);
  expect(await isScrollable()).toBe(true);
  const tabsAfter = (await groupTabs.boundingBox())!;
  const firstCardAfter = (await cards.first().boundingBox())!;
  expect(tabsAfter.x).toBeCloseTo(tabsBefore.x, 0);
  expect(tabsAfter.y).toBeCloseTo(tabsBefore.y, 0);
  expect(firstCardAfter.x).toBeCloseTo(firstCardBefore.x, 0);
  expect(firstCardAfter.y).toBeCloseTo(firstCardBefore.y, 0);
  expect(firstCardAfter.width).toBeCloseTo(firstCardBefore.width, 0);
  await page.getByRole("tab", { name: "즐겨찾기", exact: true }).click();
  await add.click();
  await expect(page).toHaveURL("/search");
  await expect(routeSearchInput(page)).toBeFocused();
});

test("홈의 + 버튼에서 그룹을 만들고 최대 개수에 도달하면 추가를 막는다", async ({ page, favorites }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/favorites");
  const add = page.getByRole("button", { name: "그룹 추가", exact: true });
  await add.click();
  const dialog = page.getByRole("dialog", { name: "그룹 추가", exact: true });
  const input = dialog.getByRole("textbox", { name: "그룹 이름", exact: true });
  await expect(input).toBeFocused();
  await expect(page.getByRole("button", { name: "즐겨찾기 더보기", exact: true })).toHaveCount(0);
  await input.fill("  아침출근즐겨찾기그룹  ");
  await input.press("Enter");
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "아침출근즐겨찾기그룹", exact: true })).toBeVisible();
  await expect(add).toBeFocused();
  await add.click();
  await input.fill("🚌 통학");
  await input.press("Enter");
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "🚌 통학", exact: true })).toBeVisible();
  await expect(add).toHaveCount(0);
  expect(favorites.requests.filter((request) => request.method === "POST").map((request) => request.body)).toEqual([
    { name: "아침출근즐겨찾기그룹" },
    { name: "🚌 통학" },
  ]);
  const school = page.getByRole("tab", { name: "🚌 통학", exact: true });
  await expect(school).toHaveAttribute("aria-selected", "true");
  await page.getByRole("link", { name: "즐겨찾기 추가하기", exact: true }).click();
  await expect(page).toHaveURL("/search");
  await page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true }).click();
  await expect(page).toHaveURL("/favorites");
  await expect(school).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: "즐겨찾기", exact: true }).click();
  await expect(page.getByRole("tab", { name: "즐겨찾기", exact: true })).toHaveAttribute("aria-selected", "true");
});

test("그룹 추가에서 잘못된 이름과 중복 이름을 안내하고 실패한 입력으로 재시도한다", async ({ page, favorites }) => {
  await page.goto("/favorites");
  await page.getByRole("button", { name: "그룹 추가", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "그룹 추가", exact: true });
  const input = dialog.getByRole("textbox", { name: "그룹 이름", exact: true });
  const submit = dialog.getByRole("button", { name: "만들기", exact: true });
  await expect(submit).toBeDisabled();
  await input.fill("열한글자는안되는이름임");
  await expect(submit).toBeDisabled();
  await input.fill("즐겨찾기");
  await submit.click();
  await expect(dialog.getByRole("alert")).toHaveText("이미 사용 중인 그룹 이름이에요.");
  await expect(input).toHaveValue("즐겨찾기");
  await input.fill("퇴근");
  favorites.mutationError = "INTERNAL_ERROR";
  await submit.click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(input).toHaveValue("퇴근");
  favorites.mutationError = null;
  await submit.click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "퇴근", exact: true })).toBeVisible();
});

test("그룹 추가 취소와 Escape는 저장하지 않고 열었던 버튼으로 돌아간다", async ({ page, favorites }) => {
  await page.goto("/favorites");
  const add = page.getByRole("button", { name: "그룹 추가", exact: true });
  await add.click();
  const dialog = page.getByRole("dialog", { name: "그룹 추가", exact: true });
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("취소할 그룹");
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(add).toBeFocused();
  await add.press("Enter");
  await expect(dialog.getByRole("textbox", { name: "그룹 이름", exact: true })).toHaveValue("");
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(add).toBeFocused();
  expect(favorites.requests.filter((request) => request.method === "POST")).toHaveLength(0);
});
