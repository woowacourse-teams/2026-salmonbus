import type { Page } from "@playwright/test";
import { test, expect, testMember } from "./fixtures/auth";
import { captureEnabled } from "./utils/capture";

const STORAGE_KEY = "salmonbus.analyticsOptOut";
const DESCRIPTION = "끄면 이 기기의 이용 기록과 화면 기록을 Amplitude(미국)로 보내지 않고, 안내와 설문도 뜨지 않아요.";

const participation = (page: Page) =>
  page.getByRole("region", { name: "개인정보" }).getByRole("switch", { name: "서비스 개선에 참여하기" });

test("고대비 모드에서 스위치의 켜짐과 꺼짐은 테두리와 시스템 색으로 구분된다", async ({ page }) => {
  await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
  await page.goto("/mypage");
  const control = participation(page);
  const thumb = control.locator("+ span");
  const colors = await page.evaluate(() => {
    const probe = document.createElement("span");
    document.body.append(probe);
    const resolved = ["Canvas", "CanvasText", "Highlight", "HighlightText"].map((color) => {
      probe.style.color = color;
      return getComputedStyle(probe).color;
    });
    probe.remove();
    return resolved;
  });
  const [canvas, canvasText, highlight, highlightText] = colors;
  await expect(control).toBeChecked();
  if (captureEnabled) {
    await page.evaluate(() => document.fonts.ready);
    await page.clock.runFor(32);
    await page.screenshot({ path: test.info().outputPath("switch-on.png") });
  }
  await expect.soft(control).toHaveCSS("border-top-style", "solid");
  await expect.soft(control).toHaveCSS("border-top-width", "2px");
  await expect.soft(control).toHaveCSS("border-top-color", highlight!);
  await expect.soft(control).toHaveCSS("background-color", highlight!);
  await expect.soft(thumb).toHaveCSS("background-color", highlightText!);
  await expect(thumb).toHaveCSS("transform", "matrix(1, 0, 0, 1, 18, 0)");

  await control.click();
  await expect(control).not.toBeChecked();
  if (captureEnabled) {
    await page.clock.runFor(32);
    await page.screenshot({ path: test.info().outputPath("switch-off.png") });
  }
  await expect.soft(control).toHaveCSS("border-top-style", "solid");
  await expect.soft(control).toHaveCSS("border-top-width", "2px");
  await expect.soft(control).toHaveCSS("border-top-color", canvasText!);
  await expect.soft(control).toHaveCSS("background-color", canvas!);
  await expect.soft(thumb).toHaveCSS("background-color", canvasText!);
  await expect(thumb).toHaveCSS("transform", "none");
});

test("비회원 마이페이지에서 서비스 개선 참여를 끄면 새로고침 뒤에도 꺼져 있고, 다시 켤 수 있다", async ({ page }) => {
  await page.goto("/mypage");
  await expect(participation(page)).toBeChecked();
  await expect(participation(page)).toHaveAccessibleDescription(DESCRIPTION);

  const toggle = page.getByRole("button", { name: "서비스 개선에 참여하기", exact: true });
  const description = page.getByText(DESCRIPTION, { exact: true });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(description).toBeHidden();
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(description).toBeVisible();
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(description).toBeHidden();
  await expect(participation(page)).toBeChecked();

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

test("어두운 고대비 모드에서 키보드로 스위치에 가면 포커스 테두리가 시스템 글자색으로 보인다", async ({ page }) => {
  await page.emulateMedia({ forcedColors: "active", colorScheme: "dark", reducedMotion: "reduce" });
  await page.goto("/mypage");
  const control = participation(page);
  const canvasText = await page.evaluate(() => {
    const probe = document.createElement("span");
    document.body.append(probe);
    probe.style.color = "CanvasText";
    const resolved = getComputedStyle(probe).color;
    probe.remove();
    return resolved;
  });
  await expect(control).toBeVisible();
  for (let step = 0; step < 40 && !(await control.evaluate((element) => element === document.activeElement)); step++) {
    await page.keyboard.press("Tab");
  }
  await expect(control).toBeFocused();
  await expect(control).not.toHaveCSS("outline-style", "none");
  await expect(control).toHaveCSS("outline-color", canvasText);
});
