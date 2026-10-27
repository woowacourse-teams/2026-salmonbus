import type { Locator, Page } from "@playwright/test";
import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

const schoolId = "10000000-0000-4000-8000-000000000003";

async function expectCancelFirst(page: Page, dialog: Locator, cancelName: string, primaryName: string) {
  const cancel = dialog.getByRole("button", { name: cancelName, exact: true });
  const primary = dialog.getByRole("button", { name: primaryName, exact: true });
  await expect(primary).toBeEnabled();
  expect((await cancel.boundingBox())!.x).toBeLessThan((await primary.boundingBox())!.x);
  await cancel.focus();
  // WebKit은 기본 설정에서 Tab으로 버튼을 건너뛰고 Alt+Tab일 때만 버튼에 포커스를 준다.
  await page.keyboard.press(page.context().browser()!.browserType().name() === "webkit" ? "Alt+Tab" : "Tab");
  await expect(primary).toBeFocused();
}

test("즐겨찾기 그룹 선택 창과 그룹 만들기 줄은 취소를 왼쪽에 둔다", async ({ page, api }) => {
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  await expectCancelFirst(page, dialog, "취소", "저장");
  await dialog.getByRole("button", { name: "새 그룹 만들기", exact: true }).click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("통학");
  await expectCancelFirst(page, dialog, "돌아가기", "만들기");
});

for (const action of ["로그아웃", "회원탈퇴"]) {
  test(`마이페이지 ${action} 확인 창은 취소를 왼쪽에 둔다`, async ({ page, favorites }) => {
    expect(favorites.groups).toHaveLength(1);
    await page.goto("/mypage");
    await page.getByRole("button", { name: new RegExp(`^${action}`) }).click();
    await expectCancelFirst(page, page.getByRole("dialog"), "취소", action);
  });
}

test("마이페이지 그룹 일괄 삭제 창은 취소를 왼쪽에 둔다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/mypage");
  const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
  await region.getByRole("button", { name: "그룹 삭제 선택", exact: true }).click();
  await region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true }).check();
  await region.getByRole("button", { name: "선택한 그룹 1개 삭제", exact: true }).click();
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
});

test("그룹 상세의 그룹 삭제와 즐겨찾기 삭제 창은 취소를 왼쪽에 둔다", async ({ page, favorites, api }) => {
  const stop = api.data.watch.stops.find((item) => item.direction === "UP" && item.boardingAllowed)!;
  favorites.groups.push({
    id: schoolId,
    name: "통학",
    favorites: [storedFavorite({ routeId, stopId: stop.stopId, direction: "UP" }, 4)],
  });
  await page.goto(`/mypage/groups/${schoolId}`);
  await expect(page.getByText(stop.name, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
  await page.getByRole("dialog").getByRole("button", { name: "취소", exact: true }).click();
  await page.getByRole("button", { name: `${stop.name} 삭제`, exact: true }).click();
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
});

test("즐겨찾기 편집의 삭제 창은 취소를 왼쪽에 둔다", async ({ page, favorites, api }) => {
  const stop = api.data.watch.stops.find((item) => item.direction === "UP" && item.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: "UP" })];
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: `${stop.name} 삭제`, exact: true }).click();
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제하기");
});

test("로그인 실패 창은 돌아가기를 왼쪽에 둔다", async ({ page, auth }) => {
  auth.loginResult = "failed";
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expectCancelFirst(page, page.getByRole("dialog"), "돌아가기", "다시 로그인");
});
