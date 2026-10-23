import { test, expect } from "./fixtures/auth";

for (const width of [320, 425]) {
  test(`${width}px 비로그인 마이페이지는 x4p8m0 안내를 표시하고 로그인 후 마이페이지로 복귀한다`, async ({
    page,
    browserName,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/mypage");
    await expect(page.getByRole("heading", { name: "연어버스에 합류 하세요!", exact: true })).toBeVisible();
    await expect(page.getByText("자주 타는 노선부터 출퇴근 그룹까지,", { exact: false })).toBeVisible();
    const profile = page.getByRole("img", { name: "살몽이", exact: true });
    expect((await profile.boundingBox())!.width).toBe(64);
    expect((await profile.boundingBox())!.height).toBe(64);
    await expect
      .poll(() => profile.evaluate((element) => element instanceof HTMLImageElement && element.naturalWidth > 0))
      .toBe(true);
    const login = page.getByRole("link", { name: "로그인 / 회원가입", exact: true });
    expect((await login.boundingBox())!.height).toBe(52);
    await expect(login).toHaveAttribute("href", "/login?returnTo=%2Fmypage");
    await expect(page.getByRole("button", { name: "로그아웃", exact: false })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "즐겨찾기 그룹 관리" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
    await page.evaluate(() => document.fonts.ready);
    await page
      .getByRole("main")
      .evaluate((element) => Promise.all(element.getAnimations().map((animation) => animation.finished)));
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: "/private/tmp/salmonbus-guest-mypage.png" });
    await login.click();
    await page.getByRole("button", { name: "카카오로 계속하기", exact: true }).click();
    await expect(page).toHaveURL("/mypage");
    await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "연어버스에 합류 하세요!", exact: true })).toHaveCount(0);
  });
}

test("로그인 확인 중이나 오류일 때는 게스트 환영 화면 대신 상태와 재시도를 표시한다", async ({ page, api }) => {
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
  await expect(page.getByRole("heading", { name: "연어버스에 합류 하세요!", exact: true })).toHaveCount(0);
  release();
  await expect(page.getByRole("alert")).toContainText("로그인 상태를 확인하지 못했어요");
  fail = false;
  await page.getByRole("button", { name: "다시 확인하기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "연어버스에 합류 하세요!", exact: true })).toBeVisible();
});
