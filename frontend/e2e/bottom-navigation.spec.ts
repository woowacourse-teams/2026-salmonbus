import { test, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ reducedMotion: "reduce" });

test("하단 탭으로 홈과 마이페이지를 오가면 현재 메뉴를 표시한다", async ({ page }) => {
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "주요 메뉴", exact: true });
  const home = nav.getByRole("link", { name: "홈", exact: true });
  const mypage = nav.getByRole("link", { name: "마이페이지", exact: true });
  await expect(home).toHaveAttribute("aria-current", "page");
  await mypage.click();
  await expect(page).toHaveURL("/mypage");
  await expect(mypage).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true })).toBeVisible();
  await home.click();
  await expect(page).toHaveURL("/");
  await expect(home).toHaveAttribute("aria-current", "page");
});

test("계정 하위 화면에는 탭을 유지하고 검색·판정 보드에는 표시하지 않는다", async ({ page, auth }) => {
  const nav = page.getByRole("navigation", { name: "주요 메뉴", exact: true });
  await page.goto("/mypage/nickname");
  await expect(nav.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
  for (const path of ["/search", `/routes/${routeId}`]) {
    await page.goto(path);
    await expect(page.getByRole("main")).toBeVisible();
    await expect(nav).toHaveCount(0);
  }
  auth.member = null;
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
  await expect(nav).toBeVisible();
  await expect(nav.locator('[aria-current="page"]')).toHaveCount(0);
});
