import { test, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ reducedMotion: "reduce" });

test("하단 탭으로 홈과 마이페이지를 오가면 현재 메뉴를 표시한다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "주요 메뉴", exact: true });
  const home = nav.getByRole("link", { name: "홈", exact: true });
  const mypage = nav.getByRole("link", { name: "마이페이지", exact: true });
  const indicator = nav.locator("[data-tab-indicator]");
  const start = (await indicator.boundingBox())!;
  const movement = await indicator.evaluateHandle((element) => ({
    finished: new Promise<number>((resolve) => {
      element.addEventListener("transitionend", (event) => {
        if (event.propertyName === "transform") resolve(element.getBoundingClientRect().x);
      });
    }),
  }));
  await expect(home).toHaveAttribute("aria-current", "page");
  await mypage.click();
  await expect(page).toHaveURL("/mypage");
  await expect(mypage).toHaveAttribute("aria-current", "page");
  expect(await movement.evaluate(({ finished }) => finished)).toBeGreaterThan(start.x);
  await movement.dispose();
  await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true })).toBeVisible();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await home.click();
  await expect(page).toHaveURL("/");
  await expect(home).toHaveAttribute("aria-current", "page");
  expect(await indicator.evaluate((element) => element.getAnimations().length)).toBe(0);
  expect((await indicator.boundingBox())!.x).toBe(start.x);
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
  await page.getByRole("link", { name: "돌아가기", exact: true }).click();
  await expect(page).toHaveURL("/");
});
