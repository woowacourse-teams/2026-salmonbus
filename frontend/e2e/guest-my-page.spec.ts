import { captureEnabled } from "./utils/capture";
import { checkAtBothWidths } from "./utils/widths";
import { test, expect } from "./fixtures/auth";

test("320px 비로그인 마이페이지의 상단 버튼으로 로그인한 뒤 마이페이지로 복귀한다", async ({ page, browserName }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/mypage");
  const login = page.getByRole("link", { name: "로그인 / 회원가입", exact: true });
  await expect(login).toHaveAttribute("href", "/login?returnTo=%2Fmypage");
  await expect(login).toBeVisible();
  await expect(page.getByRole("button", { name: "로그아웃", exact: false })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
  await page.evaluate(() => document.fonts.ready);
  await page
    .getByRole("main")
    .evaluate((element) => Promise.all(element.getAnimations().map((animation) => animation.finished)));
  await checkAtBothWidths(page, async (width) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (captureEnabled && width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-guest-mypage.png") });
  });
  await login.click();
  await page.getByRole("button", { name: "카카오로 계속하기", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  await expect(login).toHaveCount(0);
});

test("로그인 확인 중에는 상태를 표시하고 오류가 나면 다시 시도할 수 있다", async ({ page, api }) => {
  let fail = true;
  let release = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname !== "/api/auth/session" || !fail) {
      await route.fallback();
      return;
    }
    await gate;
    await route.fulfill({
      status: 503,
      json: { code: "SERVICE_UNAVAILABLE", message: "unavailable", requestId: "guest-test" },
    });
  });
  await page.goto("/mypage");
  await expect(page.getByRole("status")).toContainText("로그인 상태를 확인하고 있어요");
  const login = page.getByRole("link", { name: "로그인 / 회원가입", exact: true });
  await expect(login).toHaveCount(0);
  release();
  await expect(page.getByRole("alert")).toContainText("로그인 상태를 확인하지 못했어요");
  fail = false;
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect(login).toBeVisible();
});
