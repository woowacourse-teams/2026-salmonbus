import { test, expect, testMember } from "./fixtures/auth";

test.use({ viewport: { width: 320, height: 844 }, reducedMotion: "reduce" });

test("비로그인 마이페이지의 새 소식 줄은 지난 새 소식 목록을 열고 돌아온다", async ({ page, auth }) => {
  await page.goto("/mypage");
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "새 소식", exact: true }).click();
  await expect(page).toHaveURL("/mypage/whats-new");
  await expect(page.getByRole("heading", { level: 1, name: "새 소식", exact: true })).toBeVisible();
  await expect(page.getByText("아직 새 소식이 없어요", { exact: true })).toBeVisible();
  await expect(page.getByText("새 기능이 나오면 여기에서 알려 드릴게요", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toHaveAttribute("aria-current", "page");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await page.getByRole("link", { name: "마이페이지로 돌아가기", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByRole("link", { name: "새 소식", exact: true })).toBeVisible();

  await test.step("로그인한 마이페이지의 새 소식 줄도 같은 목록을 열고 돌아온다", async () => {
    auth.member = { ...testMember };
    await page.reload();
    await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "새 소식", exact: true }).click();
    await expect(page).toHaveURL("/mypage/whats-new");
    await expect(page.getByRole("heading", { level: 1, name: "새 소식", exact: true })).toBeVisible();
    await page.getByRole("link", { name: "마이페이지로 돌아가기", exact: true }).click();
    await expect(page).toHaveURL("/mypage");
    await expect(page.getByRole("link", { name: "새 소식", exact: true })).toBeVisible();
  });
});
