import { test, expect } from "./fixtures/favorites";
import { routeSearchInput } from "./utils/route-select";

test.use({ viewport: { width: 425, height: 844 }, reducedMotion: "reduce" });

test("로그인한 빈 홈에서 즐겨찾기 추가하기를 누르면 검색 입력창으로 이동한다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("노선을 선택하고 자주 타는 정류장의 별을 눌러 보세요", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "즐겨찾기 추가하기", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "즐겨찾기 추가하기", exact: true }).click();
  await expect(page).toHaveURL("/search");
  await expect(routeSearchInput(page)).toBeFocused();
});

test("홈의 + 버튼에서 그룹을 만들고 최대 개수에 도달하면 추가를 막는다", async ({ page, favorites }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/");
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
  await page.getByRole("tab", { name: "즐겨찾기", exact: true }).click();
  await expect(page.getByRole("tab", { name: "즐겨찾기", exact: true })).toHaveAttribute("aria-selected", "true");
});

test("그룹 추가에서 잘못된 이름과 중복 이름을 안내하고 실패한 입력으로 재시도한다", async ({ page, favorites }) => {
  await page.goto("/");
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
  await page.goto("/");
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

test("그룹이 3개면 +가 없고 그룹을 삭제하면 다시 표시한다", async ({ page, favorites }) => {
  favorites.groups.push(
    { id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] },
    { id: "10000000-0000-4000-8000-000000000004", name: "주말", favorites: [] },
  );
  await page.goto("/");
  const add = page.getByRole("button", { name: "그룹 추가", exact: true });
  await expect(page.getByRole("tab", { name: "주말", exact: true })).toBeVisible();
  await expect(add).toHaveCount(0);
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  await expect(page.getByRole("region", { name: "즐겨찾기 편집", exact: true })).toBeVisible();
  await expect(add).toHaveCount(0);
  await page.goto("/mypage");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  await page.getByRole("button", { name: "통학 그룹 삭제", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "삭제", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "완료", exact: true }).click();
  await page.goto("/");
  await expect(add).toBeVisible();
  await expect(add).toBeEnabled();
});
