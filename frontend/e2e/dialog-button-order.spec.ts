import type { Locator, Page } from "@playwright/test";
import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";
import { expectFocusRing, nextFocusKeyFor } from "./utils/focus-ring";

const schoolId = "10000000-0000-4000-8000-000000000003";

function button(dialog: Locator, name: string) {
  return dialog.getByRole("button", { name, exact: true });
}

async function expectCancelFirst(page: Page, dialog: Locator, cancelName: string, primaryName: string) {
  const cancel = button(dialog, cancelName);
  const primary = button(dialog, primaryName);
  await test.step(`${cancelName}가 ${primaryName}보다 왼쪽`, async () => {
    await expect(primary).toBeEnabled();
    expect((await cancel.boundingBox())!.x).toBeLessThan((await primary.boundingBox())!.x);
  });
  await test.step(`${cancelName}에서 Tab을 누르면 ${primaryName}에 테두리`, async () => {
    await cancel.focus();
    await page.keyboard.press(nextFocusKeyFor(page.context().browser()!.browserType().name()));
    await expectFocusRing(primary, "shown");
  });
}

test("즐겨찾기 그룹 선택 창은 열릴 때 첫 그룹에 포커스를 주고 저장 줄과 그룹 만들기 줄은 취소를 왼쪽에 둔다", async ({
  page,
  api,
}) => {
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  const checkbox = dialog.getByRole("checkbox", { name: /즐겨찾기/ });
  await expectFocusRing(checkbox, "hidden");
  await checkbox.check();
  await expectCancelFirst(page, dialog, "취소", "저장");
  await button(dialog, "새 그룹 만들기").click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("통학");
  await expectCancelFirst(page, dialog, "돌아가기", "만들기");
});

for (const action of ["로그아웃", "회원탈퇴"]) {
  test(`마이페이지 ${action} 확인 창은 열릴 때 취소에 포커스를 주고 취소를 왼쪽에 둔다`, async ({
    page,
    favorites,
  }) => {
    expect(favorites.groups).toHaveLength(1);
    await page.goto("/mypage");
    await page.getByRole("button", { name: new RegExp(`^${action}`) }).click();
    await expectFocusRing(button(page.getByRole("dialog"), "취소"), "hidden");
    await expectCancelFirst(page, page.getByRole("dialog"), "취소", action);
  });
}

test("마이페이지 그룹 일괄 삭제 창은 열릴 때 취소에 포커스를 주고 취소를 왼쪽에 둔다", async ({ page, favorites }) => {
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/mypage");
  const region = page.getByRole("region", { name: "즐겨찾기 그룹 관리", exact: true });
  await region.getByRole("button", { name: "그룹 삭제 선택", exact: true }).click();
  await region.getByRole("checkbox", { name: "통학 삭제 선택", exact: true }).check();
  await region.getByRole("button", { name: "선택한 그룹 1개 삭제", exact: true }).click();
  await expectFocusRing(button(page.getByRole("dialog"), "취소"), "hidden");
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
});

test("그룹 상세의 그룹 삭제와 즐겨찾기 삭제 창은 열릴 때 취소에 포커스를 주고 취소를 왼쪽에 둔다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.watch.stops.find((item) => item.direction === "UP" && item.boardingAllowed)!;
  favorites.groups.push({
    id: schoolId,
    name: "통학",
    favorites: [storedFavorite({ routeId, stopId: stop.stopId, direction: "UP" }, 4)],
  });
  await page.goto(`/mypage/groups/${schoolId}`);
  await expect(page.getByText(stop.name, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await expectFocusRing(button(page.getByRole("dialog"), "취소"), "hidden");
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
  await button(page.getByRole("dialog"), "취소").click();
  await page.getByRole("button", { name: `${stop.name} 삭제`, exact: true }).click();
  await expectFocusRing(button(page.getByRole("dialog"), "취소"), "hidden");
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제");
});

test("즐겨찾기 편집의 삭제 창은 열릴 때 취소에 포커스를 주고 취소를 왼쪽에 둔다", async ({ page, favorites, api }) => {
  const stop = api.data.watch.stops.find((item) => item.direction === "UP" && item.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: "UP" })];
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: `${stop.name} 삭제`, exact: true }).click();
  await expectFocusRing(button(page.getByRole("dialog"), "취소"), "hidden");
  await expectCancelFirst(page, page.getByRole("dialog"), "취소", "삭제하기");
});

test("로그인에서 돌아와 열린 실패 창은 돌아가기에 테두리 없이 포커스를 주고 돌아가기를 왼쪽에 둔다", async ({
  page,
  auth,
}) => {
  auth.loginResult = "failed";
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expectFocusRing(button(page.getByRole("dialog"), "돌아가기"), "hidden");
  await expectCancelFirst(page, page.getByRole("dialog"), "돌아가기", "다시 로그인");
});

test("키보드로 연 마이페이지 확인 창은 취소에 포커스 테두리를 보인다", async ({ page }) => {
  await page.goto("/mypage");
  await page.getByRole("button", { name: /^로그아웃/ }).focus();
  await page.keyboard.press("Enter");
  await expectFocusRing(button(page.getByRole("dialog"), "취소"), "shown");
});
