import { test, expect, storedFavorite, defaultGroupId } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

const secondGroupId = "10000000-0000-4000-8000-000000000003";
test.use({ viewport: { width: 425, height: 844 } });

test("편집 정보를 다시 조회하는 동안 홈의 명칭과 카드 위치를 유지한다", async ({ page, favorites, api }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  let release = () => {};
  const response = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/me/favorites" && !url.searchParams.has("groupId")) await response;
    await route.fallback();
  });
  await page.goto("/");
  const homeCard = page.getByRole("tabpanel").getByRole("listitem").first();
  await expect(homeCard.getByText(stop.name, { exact: true })).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
  const before = (await homeCard.boundingBox())!;
  const requested = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/me/favorites" && !url.searchParams.has("groupId");
  });
  try {
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    await requested;
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    const card = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).getByRole("listitem").first();
    await expect(card.getByText(stop.name, { exact: true })).toBeVisible();
    await expect(card.getByText(api.data.watch.route.displayName, { exact: true })).toBeVisible();
    await expect(editor.getByText("즐겨찾기를 불러오고 있어요…", { exact: true })).toHaveCount(0);
    await expect(editor.getByText("노선 정보 없음", { exact: true })).toHaveCount(0);
    await expect(editor.getByText("정류장 정보 없음", { exact: true })).toHaveCount(0);
    const pending = (await card.boundingBox())!;
    expect(pending.x).toBeCloseTo(before.x, 1);
    expect(pending.y).toBeCloseTo(before.y, 1);
    expect(pending.width).toBeCloseTo(before.width, 1);

    stop.name = "새로 확인한 정류장";
    release();
    await expect(card.getByText(stop.name, { exact: true })).toBeVisible();
    expect((await card.boundingBox())!.y).toBeCloseTo(before.y, 1);
  } finally {
    release();
  }
});

test("마이페이지 편집은 조회 중 카드 공간을 유지하고 조회 후 순서를 저장한다", async ({ page, favorites, api }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  first!.name = "첫 정류장";
  second!.name = "다음 정류장";
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  let release = () => {};
  const response = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/me/favorites" && !url.searchParams.has("groupId")) await response;
    await route.fallback();
  });
  await page.goto("/mypage");
  await page.evaluate(() => document.fonts.ready);
  const requested = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/me/favorites" && !url.searchParams.has("groupId");
  });
  try {
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    await requested;
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    await expect(editor).toHaveAttribute("aria-busy", "true");
    await expect(editor.getByRole("status").filter({ hasText: "즐겨찾기를 불러오고 있어요…" })).toHaveCSS(
      "position",
      "absolute",
    );
    await expect(editor.getByText("노선 정보 없음", { exact: true })).toHaveCount(0);
    await expect(editor.getByText("정류장 정보 없음", { exact: true })).toHaveCount(0);
    const cards = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).getByRole("listitem");
    await expect(cards).toHaveCount(2);
    const pending = (await cards.first().boundingBox())!;
    release();
    await expect(cards.first().getByText(first!.name, { exact: true })).toBeVisible();
    await expect(editor).toHaveAttribute("aria-busy", "false");
    const loaded = (await cards.first().boundingBox())!;
    expect(loaded.x).toBeCloseTo(pending.x, 1);
    expect(loaded.y).toBeCloseTo(pending.y, 1);
    expect(loaded.width).toBeCloseTo(pending.width, 1);
    expect(loaded.height).toBeCloseTo(pending.height, 1);
    await editor
      .getByRole("button", { name: `${api.data.watch.route.displayName} · ${first!.name} 순서 변경`, exact: true })
      .press("ArrowDown");
    await editor.getByRole("button", { name: "완료", exact: true }).click();
    await expect(editor).toHaveCount(0);
    expect(favorites.groups[0]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
  } finally {
    release();
  }
});

test("편집 진입과 복귀를 반복해도 카드 등장 애니메이션을 다시 실행하지 않고 포커스를 복원한다", async ({
  page,
  favorites,
  api,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  await page.goto("/");
  const panel = page.getByRole("tabpanel");
  await expect(panel.getByText(stop.name, { exact: true })).toBeVisible();
  const edit = page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true });
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await edit.click();
  await expect(editor.getByText(stop.name, { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(editor).toHaveCount(0);
  await expect(edit).toBeFocused();
  await expect(panel.locator(":scope > div")).toHaveCSS("animation-name", "none");
  await edit.click();
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(edit).toBeFocused();
  await expect(panel.getByText(stop.name, { exact: true })).toBeVisible();
  await expect(panel.locator(":scope > div")).toHaveCSS("animation-name", "none");
});

test("홈과 마이페이지 편집 전환은 모션 줄이기 설정을 따른다", async ({ page, favorites }) => {
  favorites.groups[0]!.name = "출퇴근";
  for (const path of ["/", "/mypage"]) {
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.goto(path);
    const edit = page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true });
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    await edit.click();
    await expect(editor.locator("..")).not.toHaveCSS("animation-name", "none");
    await expect(editor.locator("..")).toHaveCSS("animation-duration", "0.22s");
    await page.keyboard.press("Escape");
    await expect(edit).toBeFocused();
    await page.emulateMedia({ reducedMotion: "reduce" });
    await edit.click();
    await expect(editor.locator("..")).toHaveCSS("animation-name", "none");
    await editor.getByRole("button", { name: "완료", exact: true }).click();
    await expect(editor).toHaveCount(0);
    await expect(edit).toBeFocused();
  }
});

test("홈 영역이 편집 화면으로 바뀌고 카드를 잡고 이동·놓은 뒤 완료 토스트를 표시한다", async ({
  page,
  favorites,
  api,
  browserName,
}, testInfo) => {
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  first!.name = "야탑역.종합버스터미널";
  second!.name = "안양역.시외버스터미널";
  api.data.watch.route.directions[0].terminalStopName = "안양역";
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
  await expect(
    page.getByRole("textbox", { name: "번호를 입력하면 탑승 확률을 알려드려요", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toBeVisible();
  const cards = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).getByRole("listitem");
  await expect(cards.first()).toContainText(first!.name);
  await page.evaluate(() => document.fonts.ready);
  if (browserName === "chromium") await page.screenshot({ path: test.info().outputPath("editor.png") });
  const grip = editor.getByRole("button", {
    name: `${api.data.watch.route.displayName} · ${first!.name} 순서 변경`,
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
  if (browserName === "chromium") await page.screenshot({ path: test.info().outputPath("editor-drag.png") });
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
  if (browserName === "chromium") await page.screenshot({ path: test.info().outputPath("editor-complete.png") });
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
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
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
  if (browserName === "chromium") await page.screenshot({ path: test.info().outputPath("editor-delete.png") });
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

for (const width of [320, 425]) {
  test(`편집 카드는 ${width}px에서 홈의 글자 크기를 유지하고 긴 정류장명을 모두 표시한다`, async ({
    page,
    favorites,
    api,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
    stop.name = "분당서울대학교병원.장례식장.헬스케어혁신파크";
    api.data.watch.route.directions[0].terminalStopName = "안양역";
    favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
    await page.goto("/");
    const panel = page.getByRole("tabpanel");
    const homeName = panel.getByText(stop.name, { exact: true });
    const homeBadge = panel.getByText(api.data.watch.route.displayName, { exact: true });
    await expect(homeName).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const homeNameFont = await homeName.evaluate((element) => getComputedStyle(element).fontSize);
    const homeNameWeight = await homeName.evaluate((element) => getComputedStyle(element).fontWeight);
    const homeBadgeFont = await homeBadge.evaluate((element) => getComputedStyle(element).fontSize);
    const homeNameHeight = (await homeName.boundingBox())!.height;
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    const name = editor.getByText(stop.name, { exact: true });
    await expect(name).toBeVisible();
    await expect(name).toHaveCSS("font-size", homeNameFont);
    await expect(name).toHaveCSS("font-weight", homeNameWeight);
    await expect(editor.getByText(api.data.watch.route.displayName, { exact: true })).toHaveCSS(
      "font-size",
      homeBadgeFont,
    );
    expect((await name.boundingBox())!.height).toBeGreaterThan(homeNameHeight);
    expect(await name.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(editor.getByRole("button", { name: `${stop.name} 삭제`, exact: true })).toBeVisible();
    await expect(
      editor.getByRole("button", { name: `${api.data.watch.route.displayName} · ${stop.name} 순서 변경`, exact: true }),
    ).toBeVisible();
  });
}

for (const width of [320, 425])
  test(`긴 그룹 이름 세 개와 정류장 이름도 ${width}px 편집 영역을 넘지 않고 취소할 수 있다`, async ({
    page,
    favorites,
    api,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.boardingAllowed)!;
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
  const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
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
  await groupList.evaluate((element) =>
    Promise.all(element.getAnimations({ subtree: true }).map((animation) => animation.finished)),
  );
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

for (const width of [320, 425]) {
  test(`${width}px 홈 편집은 손잡이 자리만 추가하고 탭 높이와 카드 테두리를 유지한다`, async ({
    page,
    favorites,
    api,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
    favorites.groups[0]!.name = "출퇴근";
    favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
    favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
    await page.goto("/");
    const normalCard = page.getByRole("tabpanel").getByRole("listitem").first();
    await expect(normalCard).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    await page
      .getByRole("tabpanel")
      .evaluate((element) =>
        Promise.all(element.getAnimations({ subtree: true }).map((animation) => animation.finished)),
      );
    const tabs = await page.getByRole("tab").evaluateAll((elements) =>
      elements.map((element) => {
        const box = element.getBoundingClientRect();
        return {
          name: element.textContent!,
          x: box.x,
          y: box.y,
          width: box.width,
          height: box.height,
          background: getComputedStyle(element).backgroundColor,
        };
      }),
    );
    const surface = await normalCard.evaluate((element) => {
      const s = getComputedStyle(element);
      const box = element.getBoundingClientRect();
      return {
        radius: s.borderRadius,
        outline: s.outline,
        offset: s.outlineOffset,
        background: s.backgroundColor,
        x: box.x,
        y: box.y,
        width: box.width,
      };
    });
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    await editor
      .getByRole("list", { name: "그룹 순서", exact: true })
      .evaluate((element) =>
        Promise.all(element.getAnimations({ subtree: true }).map((animation) => animation.finished)),
      );
    for (const [index, tab] of tabs.entries()) {
      const button = editor.getByRole("button", { name: tab.name, exact: true });
      const group = button.locator("..");
      const box = (await group.boundingBox())!;
      expect(box.height).toBe(37);
      expect(box.width).toBeCloseTo(tab.name === "출퇴근" ? 81 : 69, 0);
      expect(tab.width).toBeCloseTo(tab.name === "출퇴근" ? 60 : 48, 0);
      expect(box.width).toBeCloseTo(tab.width + 21, 1);
      expect(box.x).toBeCloseTo(tab.x + index * 21, 1);
      expect(box.y).toBeCloseTo(tab.y, 1);
      expect(box.height).toBeCloseTo(tab.height, 1);
      await expect(group).toHaveCSS("background-color", tab.background);
      const handle = group.getByRole("button", { name: `${tab.name} 순서 변경`, exact: true });
      const grip = (await handle.boundingBox())!;
      const textRight = await button.evaluate((element) => {
        const range = document.createRange();
        range.selectNodeContents(element);
        return range.getBoundingClientRect().right;
      });
      expect(grip.x - textRight).toBeCloseTo(6, 1);
      expect(grip.width).toBe(15);
      expect(box.x + box.width - grip.x - grip.width).toBeCloseTo(12, 1);
      expect(grip.x).toBeGreaterThan(box.x + box.width / 2);
      expect(grip.x + grip.width).toBeLessThanOrEqual(box.x + box.width);
    }
    const card = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true }).locator("li").first();
    await expect(card.getByText(stop.name, { exact: true })).toBeVisible();
    const box = (await card.boundingBox())!;
    expect(box.x).toBeCloseTo(surface.x, 1);
    expect(box.y).toBeCloseTo(surface.y, 1);
    expect(box.width).toBeCloseTo(surface.width, 1);
    await expect(card).toHaveCSS("border-radius", surface.radius);
    await expect(card).toHaveCSS("outline", surface.outline);
    await expect(card).toHaveCSS("outline-offset", surface.offset);
    await expect(card).toHaveCSS("background-color", surface.background);
    if (width === 425)
      await page.screenshot({ path: test.info().outputPath(`salmonbus-srksd-${test.info().project.name}.png`) });
  });
}

test("편집을 누를 때만 손잡이 공간이 열리며 모션 줄이기에서는 즉시 표시한다", async ({ page, favorites }) => {
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const handle = page.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true });
  const samples = await handle.evaluate((element) => {
    void getComputedStyle(element).width;
    const animation = element.getAnimations()[0];
    if (!animation) return null;
    animation.pause();
    animation.currentTime = 0;
    const start = parseFloat(getComputedStyle(element).width);
    const startOpacity = getComputedStyle(element).opacity;
    animation.currentTime = Number(animation.effect!.getTiming().duration) / 2;
    const middle = parseFloat(getComputedStyle(element).width);
    animation.finish();
    return {
      start,
      middle,
      end: parseFloat(getComputedStyle(element).width),
      startOpacity,
      endOpacity: getComputedStyle(element).opacity,
    };
  });
  expect(samples).not.toBeNull();
  expect(samples!.start).toBe(0);
  expect(samples!.middle).toBeGreaterThan(0);
  expect(samples!.middle).toBeLessThan(15);
  expect(samples!.end).toBe(15);
  expect(samples!.startOpacity).toBe("0");
  expect(samples!.endOpacity).toBe("1");
  await page.keyboard.press("Escape");
  await expect(handle).toHaveCount(0);
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const groups = page.getByRole("list", { name: "그룹 순서", exact: true });
  await groups.evaluate((element) =>
    element.getAnimations({ subtree: true }).forEach((animation) => {
      animation.pause();
      animation.currentTime = Number(animation.effect!.getTiming().duration) / 2;
    }),
  );
  const grip = (await handle.boundingBox())!;
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
  await page.mouse.down();
  await page.mouse.move(grip.x + 200, grip.y + grip.height / 2, { steps: 8 });
  await page.mouse.up();
  await expect(groups.getByRole("listitem").first()).toContainText("통학");
  await page.keyboard.press("Escape");
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  await expect(handle).toHaveCSS("animation-name", "none");
  await expect(handle).toHaveCSS("width", "15px");
});

for (const width of [320, 425]) {
  test(`${width}px 편집 중 빈 그룹은 CnyGe 안내를 표시하고 그룹 전환과 마지막 항목 삭제에도 반영한다`, async ({
    page,
    favorites,
    api,
    browserName,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
    favorites.groups.push({
      id: secondGroupId,
      name: "통학",
      favorites: [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })],
    });
    await page.goto("/");
    await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
    const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
    const empty = editor.getByText("아직 등록한 정류장이 없어요", { exact: true }).locator("..");
    await expect(empty).toBeVisible();
    await expect(empty.getByText("0 / 2", { exact: true })).toHaveCSS("color", "rgb(232, 83, 63)");
    await expect(empty).toHaveCSS("padding-top", "36px");
    await expect(empty).toHaveCSS("padding-bottom", "36px");
    await expect(empty).toHaveCSS("gap", "8px");
    await expect(empty).toHaveCSS("text-align", "center");
    await expect(editor.getByText("아직 등록한 즐겨찾기가 없어요.", { exact: true })).toHaveCount(0);
    await expect(editor.getByRole("list", { name: "즐겨찾기 순서", exact: true })).toHaveCount(0);
    await expect(editor.getByRole("button", { name: "완료", exact: true })).toBeEnabled();
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-cnyge.png") });
    await editor.getByRole("button", { name: "통학", exact: true }).click();
    await expect(empty).toHaveCount(0);
    const remove = editor.getByRole("button", { name: `${stop.name} 삭제`, exact: true });
    await expect(remove).toBeVisible();
    await remove.click();
    await page.getByRole("dialog").getByRole("button", { name: "삭제하기", exact: true }).click();
    await expect(empty).toBeVisible();
    expect(favorites.groups[1]!.favorites).toHaveLength(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await editor.getByRole("button", { name: "완료", exact: true }).click();
    await expect(editor).toHaveCount(0);
  });
}
