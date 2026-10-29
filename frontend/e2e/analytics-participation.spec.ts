import type { Page } from "@playwright/test";
import { test, expect, testMember } from "./fixtures/auth";

const STORAGE_KEY = "salmonbus.analyticsOptOut";
const DESCRIPTION = "끄면 이 기기의 이용 기록과 화면 기록을 Amplitude(미국)로 보내지 않고, 안내와 설문도 뜨지 않아요.";

const participation = (page: Page) =>
  page.getByRole("region", { name: "개인정보" }).getByRole("switch", { name: "서비스 개선에 참여하기" });

test("비회원 마이페이지에서 서비스 개선 참여를 끄면 새로고침 뒤에도 꺼져 있고, 다시 켤 수 있다", async ({ page }) => {
  await page.goto("/mypage");
  await expect(participation(page)).toBeChecked();
  await expect(participation(page)).toHaveAccessibleDescription(DESCRIPTION);

  await participation(page).click();
  await expect(participation(page)).not.toBeChecked();
  expect(await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)).toBe("true");

  await page.reload();
  await expect(participation(page)).not.toBeChecked();

  await participation(page).click();
  await expect(participation(page)).toBeChecked();
  expect(await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)).toBeNull();
});

test("회원 마이페이지는 회원탈퇴 아래 개인정보 묶음에 같은 스위치를 두고, 끈 상태를 이어서 보여 준다", async ({
  page,
  auth,
}) => {
  auth.member = { ...testMember };
  await page.addInitScript((key) => localStorage.setItem(key, "true"), STORAGE_KEY);
  await page.goto("/mypage");
  await expect(participation(page)).not.toBeChecked();
  await expect(participation(page)).toHaveAccessibleDescription(DESCRIPTION);

  const withdraw = (await page.getByRole("button", { name: /회원탈퇴/ }).boundingBox())!;
  const privacy = (await page.getByRole("region", { name: "개인정보" }).boundingBox())!;
  expect(privacy.y).toBeGreaterThan(withdraw.y + withdraw.height);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("같은 브라우저의 다른 탭에서 끄거나 켜면 열려 있는 마이페이지 스위치도 바로 따라간다", async ({
  page,
  context,
}) => {
  const other = await context.newPage();
  await other.route("**/api/auth/session", (route) =>
    route.fulfill({ json: { authenticated: false, refreshable: false }, headers: { "Cache-Control": "no-store" } }),
  );
  await page.goto("/mypage");
  await other.goto("/mypage");
  await expect(participation(other)).toBeChecked();

  await participation(page).click();
  await expect(participation(other)).not.toBeChecked();

  await participation(page).click();
  await expect(participation(other)).toBeChecked();
});
