import { test, expect, storedFavorite, defaultGroupId } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

const secondGroupId = "10000000-0000-4000-8000-000000000003";
test.use({ viewport: { width: 425, height: 844 } });

test("홈 영역이 편집 화면으로 바뀌고 카드를 잡고 이동·놓은 뒤 완료 토스트를 표시한다", async ({
  page,
  favorites,
  api,
  browserName,
}, testInfo) => {
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  first!.name = "야탑역.종합버스터미널";
  second!.name = "안양역.시외버스터미널";
  api.data.board.route.directions[0].terminalStopName = "안양역";
  favorites.groups[0]!.name = "출퇴근";
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: /노선/ })).toBeVisible();
  await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toBeVisible();
  const cards = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).getByRole("listitem");
  await expect(cards.first()).toContainText(first!.name);
  await page.evaluate(() => document.fonts.ready);
  if (browserName === "chromium") await page.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/editor.png" });
  const grip = editor.getByRole("button", {
    name: `${api.data.board.route.displayName} · ${first!.name} 순서 변경`,
    exact: true,
  });
  const handle = (await grip.boundingBox())!;
  const before = (await cards.first().boundingBox())!;
  const secondBox = (await cards.nth(1).boundingBox())!;
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2 + 30, { steps: 6 });
  const lifted = (await cards.first().boundingBox())!;
  expect(lifted.y - before.y).toBeGreaterThan(25);
  expect(lifted.y - before.y).toBeLessThan(35);
  expect(lifted.width).toBeGreaterThan(before.width);
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2 + secondBox.y - before.y, {
    steps: 12,
  });
  if (browserName === "chromium") await page.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/editor-drag.png" });
  await page.mouse.up();
  await expect(cards.first()).toContainText(second!.name);
  expect(favorites.groups[0]!.favorites[0]!.stopId).toBe(first!.stopId);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  const toast = page.getByRole("status").filter({ hasText: "변경 사항을 저장했어요" });
  await expect(toast).toBeVisible();
  await expect(page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" })).toBeFocused();
  expect(favorites.groups[0]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
  await toast.evaluate((element) => Promise.all(element.getAnimations().map((animation) => animation.finished)));
  const toastBox = (await toast.boundingBox())!;
  const nav = (await page.getByRole("link", { name: "홈", exact: true }).boundingBox())!;
  expect(toastBox.y + toastBox.height).toBeLessThan(nav.y);
  await testInfo.attach("즐겨찾기 편집 완료", { body: await page.screenshot(), contentType: "image/png" });
  if (browserName === "chromium")
    await page.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/editor-complete.png" });
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(toast).toBeVisible();
  expect(favorites.requests.filter((request) => request.method === "PUT")).toHaveLength(1);
});

test("편집 중 삭제는 확인·실패·재시도가 가능하고 다른 그룹과 미저장 그룹 순서를 유지한다", async ({
  page,
  favorites,
  api,
  browserName,
}) => {
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  const target = { routeId, stopId: first!.stopId, direction: "UP" as const };
  const removed = storedFavorite(target);
  const kept = storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5);
  favorites.groups[0]!.name = "출퇴근";
  favorites.groups[0]!.favorites = [removed, kept];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [storedFavorite(target, 4)] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: "출퇴근 순서 변경", exact: true }).press("ArrowRight");
  await editor.getByRole("button", { name: new RegExp(`${first!.name} 순서 변경$`) }).press("ArrowDown");
  const trash = editor.getByRole("button", { name: `${first!.name} 삭제`, exact: true });
  await trash.click();
  const dialog = page.getByRole("dialog", { name: "즐겨찾기를 삭제할까요?" });
  await expect(dialog).toContainText("출퇴근 그룹에서만 삭제돼요.");
  await expect(dialog).toContainText("필요하면 언제든 다시 추가할 수 있어요.");
  if (browserName === "chromium") await page.screenshot({ path: "/private/tmp/salmonbus-sal249-qa/editor-delete.png" });
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(trash).toBeFocused();
  expect(favorites.requests.filter((request) => request.method === "DELETE")).toHaveLength(0);
  await trash.click();
  favorites.mutationError = "SERVICE_UNAVAILABLE";
  await dialog.getByRole("button", { name: "삭제하기", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(2);
  favorites.mutationError = null;
  await dialog.getByRole("button", { name: "삭제하기", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(trash).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeFocused();
  await expect(editor.getByText(second!.name, { exact: true })).toBeVisible();
  expect(favorites.groups[0]!.favorites).toEqual([kept]);
  expect(favorites.groups[1]!.favorites).toHaveLength(1);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([secondGroupId, defaultGroupId]);
  expect(favorites.requests.filter((request) => request.method === "PUT").map((request) => request.path)).toEqual([
    "/api/me/favorite-groups/order",
  ]);
});

for (const width of [320, 425])
  test(`긴 그룹 이름 세 개와 정류장 이름도 ${width}px 편집 영역을 넘지 않고 취소할 수 있다`, async ({
    page,
    favorites,
    api,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.board.stops.find((stop) => stop.boardingAllowed)!;
    stop.name = "긴정류장이름.종합버스터미널입구";
    favorites.groups[0]!.name = "일이삼사오육칠팔구십";
    favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
    favorites.groups.push(
      { id: secondGroupId, name: "일이삼사오육칠팔구구", favorites: [] },
      { id: "10000000-0000-4000-8000-000000000004", name: "통학", favorites: [] },
    );
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/");
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    await expect(editor.getByText(stop.name, { exact: true })).toBeVisible();
    expect(await editor.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const handle = editor.getByRole("button", { name: "일이삼사오육칠팔구십 순서 변경", exact: true });
    await handle.press("ArrowRight");
    await handle.press("Escape");
    await expect(editor).toHaveCount(0);
    expect(favorites.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
  });

test("터치 손잡이를 놓으면 순서가 바뀌고 pointercancel이면 원래 자리로 돌아간다", async ({
  page,
  context,
  browserName,
  favorites,
  api,
}) => {
  test.skip(browserName !== "chromium", "실제 터치 입력은 Chromium CDP에서 검증한다.");
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const grip = editor.getByRole("button", { name: new RegExp(`${first!.name} 순서 변경$`) });
  const cards = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).getByRole("listitem");
  const handle = (await grip.boundingBox())!;
  const card = (await cards.first().boundingBox())!;
  const next = (await cards.nth(1).boundingBox())!;
  const cdp = await context.newCDPSession(page);
  await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: true });
  const point = { x: handle.x + handle.width / 2, y: handle.y + handle.height / 2 };
  for (const end of ["touchCancel", "touchEnd"] as const) {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [point] });
    await cdp.send("Input.dispatchTouchEvent", {
      type: "touchMove",
      touchPoints: [{ ...point, y: point.y + next.y - card.y }],
    });
    await cdp.send("Input.dispatchTouchEvent", { type: end, touchPoints: [] });
    await expect(cards.first()).toContainText(end === "touchCancel" ? first!.name : second!.name);
    await cards.evaluateAll((elements) =>
      Promise.all(elements.flatMap((element) => element.getAnimations().map((animation) => animation.finished))),
    );
  }
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups[0]!.favorites[0]!.stopId).toBe(second!.stopId);
});

test("너비가 다른 그룹 세 개도 가로 손잡이로 맨 뒤에서 맨 앞으로 이동한다", async ({ page, favorites }) => {
  const thirdGroupId = "10000000-0000-4000-8000-000000000004";
  favorites.groups[0]!.name = "출퇴근용즐겨찾기";
  favorites.groups.push(
    { id: secondGroupId, name: "통학", favorites: [] },
    { id: thirdGroupId, name: "주말", favorites: [] },
  );
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  const groupList = editor.getByRole("list", { name: "그룹 순서", exact: true });
  const from = (await groupList.getByRole("button", { name: "주말 순서 변경", exact: true }).boundingBox())!;
  const to = (await groupList.getByRole("button", { name: "출퇴근용즐겨찾기 순서 변경", exact: true }).boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x - 50, to.y + to.height / 2, { steps: 16 });
  await page.mouse.up();
  await expect(groupList.getByRole("listitem").first()).toContainText("주말");
  await expect(groupList.getByRole("button", { name: "출퇴근용즐겨찾기", exact: true })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([thirdGroupId, defaultGroupId, secondGroupId]);
});

test("편집 중 홈 로고를 누르면 미저장 순서를 버리고 일반 홈으로 돌아간다", async ({ page, favorites }) => {
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true }).press("ArrowRight");
  await page.getByRole("link", { name: "Salmonbus 홈으로 이동", exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "즐겨찾기", exact: true })).toBeVisible();
  expect(favorites.groups.map((group) => group.id)).toEqual([defaultGroupId, secondGroupId]);
  expect(favorites.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
});

test("편집 중 서버 그룹이 바뀌면 오류 화면을 닫아 새 목록으로 다시 저장할 수 있다", async ({ page, favorites }) => {
  const thirdGroupId = "10000000-0000-4000-8000-000000000004";
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await editor.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true }).press("ArrowRight");
  favorites.groups.push({ id: thirdGroupId, name: "주말", favorites: [] });
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor.getByRole("alert")).toContainText("목록이 변경됐어요");
  await editor.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(page.getByRole("tab", { name: "주말", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  await editor.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true }).press("ArrowRight");
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([secondGroupId, defaultGroupId, thirdGroupId]);
});
