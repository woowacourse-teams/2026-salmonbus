import { test, expect } from "./fixtures/auth";

for (const width of [320, 425]) {
  test(`비회원 홈 안내 카드는 ${width}px에서 검색·로그인과 함께 동작한다`, async ({ page, browserName }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/");
    const card = page.getByRole("region", { name: "내 정류장을 저장해 보세요", exact: true });
    await expect(card).toBeVisible();
    await expect(card).toContainText("로그인을 하면 자주 타는 노선과 정류장을");
    await expect(card).toContainText("내 그룹에 모아 볼 수 있어요.");
    await expect(card.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toHaveAttribute(
      "href",
      "/login?returnTo=%2F",
    );
    await page.evaluate(() => document.fonts.ready);
    expect(await card.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await testInfo.attach("비회원 홈 안내", { body: await page.screenshot(), contentType: "image/png" });
    if (browserName === "chromium" && width === 425) {
      await page.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/anonymous-home.png" });
      await card.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/anonymous-card.png" });
    }

    await page.getByRole("textbox", { name: "노선 번호", exact: true }).fill("3330");
    await expect(card).toHaveCount(0);
    await expect(page.getByRole("region", { name: "검색 결과", exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Salmonbus 홈으로 이동", exact: true }).click();
    await expect(card).toBeVisible();
    await card.getByRole("link", { name: "로그인 / 회원가입", exact: true }).click();
    await expect(page).toHaveURL("/login?returnTo=%2F");
    await page.getByRole("button", { name: "카카오로 계속하기", exact: true }).click();
    await expect(page).toHaveURL("/");
    await expect(card).toHaveCount(0);
    await expect(page.getByText("등록된 즐겨찾기가 없어요", { exact: true })).toBeVisible();
  });
}
