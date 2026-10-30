import { mergeTests } from "@playwright/test";
import { test as favoritesTest, expect } from "./fixtures/favorites";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";
import { captureEnabled } from "./utils/capture";
import { checkAtBothWidths } from "./utils/widths";

const test = mergeTests(mapTest, favoritesTest);
test.use({ reducedMotion: "reduce" });

test("비회원 즐겨찾기 팝업은 320px에서 닫기·로그인 복귀를 지원한다", async ({
  page,
  auth,
  api,
  favorites,
  browserName,
}, testInfo) => {
  auth.member = null;
  await page.setViewportSize({ width: 320, height: 844 });
  const stop = api.data.watch.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
  const destination = `/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`;
  await page.goto(destination);
  const star = page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true });
  const dialog = page.getByRole("dialog", { name: "로그인하면 즐겨찾기를 등록할 수 있어요", exact: true });
  const login = dialog.getByRole("link", { name: "로그인 / 회원가입", exact: true });

  await test.step("별을 누르면 로그인 안내 창이 화면 안에 열린다", async () => {
    await star.click();
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("로그인을 하면 자주 타는 노선과 정류장을");
    await expect(dialog).toContainText("내 그룹에 모아 볼 수 있어요.");
    await expect(login).toHaveAttribute("href", `/login?${new URLSearchParams({ returnTo: destination })}`);
    await page.evaluate(() => document.fonts.ready);
    await checkAtBothWidths(page, async (width) => {
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
      const bounds = (await dialog.boundingBox())!;
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(844);
      if (captureEnabled && width === 425) {
        await testInfo.attach("비회원 즐겨찾기 로그인 안내", {
          body: await page.screenshot(),
          contentType: "image/png",
        });
        if (browserName === "chromium") {
          await page.screenshot({ path: test.info().outputPath("favorite-login.png") });
          await dialog.screenshot({ path: test.info().outputPath("favorite-login-dialog.png") });
        }
      }
    });
  });

  await test.step("닫으면 별로 포커스가 돌아가고 저장하지 않는다", async () => {
    await dialog.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(star).toBeFocused();
    expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);
  });

  await test.step("로그인하면 방면과 정류장이 담긴 같은 판정보드로 돌아온다", async () => {
    await star.click();
    await login.click();
    await page.getByRole("button", { name: "카카오로 계속하기", exact: true }).click();
    await expect(page).toHaveURL(destination);
    await expect(
      page.getByRole("group", { name: "운행 방향 선택" }).getByRole("button", { pressed: true }),
    ).toContainText("방면");
    await expect(
      page.locator(`[data-stop-sequence="${stop.sequence}"]`).getByRole("button", { expanded: true }).first(),
    ).toBeVisible();
    await expect(star).toHaveAttribute("aria-pressed", "false");
    expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);
  });

  await test.step("돌아온 판정보드에서 별을 다시 눌러 저장한다", async () => {
    await star.click();
    const selection = page.getByRole("dialog", { name: "저장할 그룹 선택" });
    await selection.getByRole("checkbox", { name: /즐겨찾기/ }).check();
    await selection.getByRole("button", { name: "저장", exact: true }).click();
    await expect(selection).not.toBeVisible();
    await expect(star).toHaveAttribute("aria-pressed", "true");
    expect(favorites.groups[0]!.favorites).toEqual([
      expect.objectContaining({ routeId, stopId: stop.stopId, direction: "DOWN" }),
    ]);
  });
});
