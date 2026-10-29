import { test, expect } from "./fixtures/favorites";
import { finishSearchNavigation, routeSearchEntry, routeSearchInput, routeButton } from "./utils/route-select";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

test("키보드로 검색과 판정보드를 이동하면 화면 제목을 구분하고 검색 초점을 유지한다", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle("홈 | 연어");
  await expect(page.locator("head > title")).toHaveCount(1);
  await routeSearchEntry(page).press("Enter");
  await finishSearchNavigation(page);
  await expect(page).toHaveTitle("노선 검색 | 연어");
  await expect(page.locator("head > title")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 1, name: "노선 검색", exact: true })).toHaveCount(1);
  await expect(routeSearchInput(page)).toBeFocused();
  await routeSearchInput(page).fill("3330");
  await routeButton(page).press("Enter");
  await expect(page).toHaveTitle("노선 판정보드 | 연어");
  await expect(page.getByRole("heading", { level: 1, name: "3330", exact: true })).toBeVisible();
});

test("계정과 그룹 화면은 각 기능을 문서 제목으로 알린다", async ({ page }) => {
  await page.goto("/mypage");
  await expect(page).toHaveTitle("마이페이지 | 연어");
  await page.getByRole("link", { name: "닉네임 수정", exact: true }).press("Enter");
  await expect(page).toHaveTitle("닉네임 수정 | 연어");
  await expect(page.getByRole("textbox", { name: "닉네임", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "마이페이지로 돌아가기", exact: true }).press("Enter");
  await page.getByRole("link", { name: "새 그룹 만들기", exact: true }).press("Enter");
  await expect(page).toHaveTitle("즐겨찾기 그룹 관리 | 연어");
  await expect(page.getByRole("textbox", { name: "그룹 이름", exact: true })).toBeVisible();
  await page.goto("/login");
  await expect(page).toHaveTitle("로그인 | 연어");
});

test("판정보드 조회 실패는 경고로 알리고 제목 탐색을 유지한다", async ({ page, api }) => {
  api.mode.watch = "network-error";
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByRole("heading", { level: 1, name: "노선 판정보드", exact: true })).toHaveCount(1);
  await expect(page.getByRole("alert")).toBeVisible();
});
