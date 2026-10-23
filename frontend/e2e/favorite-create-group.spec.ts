import { test, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

for (const width of [320, 425]) {
  test(`${width}px 판정보드 모달에서 새 그룹을 만들고 기존 선택과 함께 저장한다`, async ({
    page,
    favorites,
    api,
    browserName,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
    await page.goto(`/routes/${routeId}`);
    await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
    await expect(dialog.getByText(stop.name, { exact: false })).toHaveCount(0);
    await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
    await dialog.getByRole("button", { name: "새 그룹 만들기", exact: true }).click();
    const input = dialog.getByRole("textbox", { name: "그룹 이름", exact: true });
    await expect(input).toBeFocused();
    await input.fill("  🚌 통학  ");
    await expect(dialog.getByRole("button", { name: "저장", exact: true })).toHaveCount(0);
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-create-group-form.png") });
    await input.press("Enter");
    await expect(dialog.getByRole("textbox")).toHaveCount(0);
    await expect(dialog.getByRole("checkbox", { name: /🚌 통학/ })).toBeChecked();
    await expect(dialog.getByRole("checkbox", { name: /즐겨찾기/ })).toBeChecked();
    expect(favorites.requests.find((request) => request.method === "POST")?.body).toEqual({ name: "🚌 통학" });
    expect(favorites.requests.some((request) => request.path === "/api/me/favorite-selections")).toBe(false);
    const save = dialog.getByRole("button", { name: "저장", exact: true });
    await expect(save).toBeEnabled();
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-create-group-selected.png") });
    await save.click();
    await expect(dialog).toHaveCount(0);
    expect(
      favorites.requests.find((request) => request.path === "/api/me/favorite-selections")?.body?.favoriteGroupIds,
    ).toEqual(favorites.groups.map((group) => group.id));
    expect(favorites.groups.every((group) => group.favorites.some((favorite) => favorite.stopId === stop.stopId))).toBe(
      true,
    );
    await expect(page.getByText("즐겨찾기를 저장했습니다", { exact: true })).toBeVisible();
  });
}

test("그룹 이름 검증·중복·생성 실패 후에도 입력과 기존 선택을 유지하며 재시도한다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  await dialog.getByRole("button", { name: "새 그룹 만들기", exact: true }).click();
  const input = dialog.getByRole("textbox", { name: "그룹 이름", exact: true });
  const create = dialog.getByRole("button", { name: "만들기", exact: true });
  await expect(create).toBeDisabled();
  await input.fill("12345678901");
  await expect(create).toBeDisabled();
  await expect(input).toHaveAttribute("aria-invalid", "true");
  expect(favorites.requests.filter((request) => request.method === "POST")).toHaveLength(0);
  await input.fill("즐겨찾기");
  await create.click();
  await expect(dialog.getByRole("alert")).toContainText("이미 사용 중");
  await expect(input).toHaveValue("즐겨찾기");
  await input.fill("통학");
  favorites.mutationError = "INTERNAL_ERROR";
  await create.click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(input).toHaveValue("통학");
  await expect(dialog.getByRole("checkbox", { name: /즐겨찾기/ })).toBeChecked();
  expect(favorites.groups).toHaveLength(1);
  favorites.mutationError = null;
  await create.click();
  await expect(dialog.getByRole("checkbox", { name: /통학/ })).toBeChecked();
  await expect(dialog.getByRole("checkbox", { name: /즐겨찾기/ })).toBeChecked();
});

test("그룹 생성 취소는 요청하지 않고 생성 후 모달 취소는 그룹만 남긴다", async ({ page, favorites, api }) => {
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  const star = page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true });
  await star.click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  const add = dialog.getByRole("button", { name: "새 그룹 만들기", exact: true });
  await add.click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("미생성");
  await dialog.getByRole("button", { name: "돌아가기", exact: true }).click();
  expect(favorites.requests.filter((request) => request.method === "POST")).toHaveLength(0);
  await add.click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("통학");
  await dialog.getByRole("button", { name: "만들기", exact: true }).click();
  await expect(dialog.getByRole("checkbox", { name: /통학/ })).toBeChecked();
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(star).toBeFocused();
  await expect(star).toHaveAttribute("aria-pressed", "false");
  expect(favorites.groups.map((group) => group.name)).toEqual(["즐겨찾기", "통학"]);
  expect(favorites.groups.every((group) => group.favorites.length === 0)).toBe(true);
  await star.click();
  await expect(dialog.getByRole("checkbox", { name: /통학/ })).not.toBeChecked();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
});

test("세 번째 그룹을 만들면 추가 버튼을 숨기고 즐겨찾기 저장은 유지한다", async ({ page, favorites, api }) => {
  favorites.groups.push({ id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] });
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("button", { name: "새 그룹 만들기", exact: true }).click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("주말");
  await dialog.getByRole("button", { name: "만들기", exact: true }).click();
  await expect(dialog.getByRole("checkbox", { name: /주말/ })).toBeChecked();
  await expect(dialog.getByRole("checkbox")).toHaveCount(3);
  await expect(dialog.getByRole("button", { name: "새 그룹 만들기", exact: true })).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "저장", exact: true })).toBeEnabled();
});

test("그룹 생성 후 목록 갱신이 느려도 누락 오류나 중복 생성을 유도하지 않는다", async ({ page, favorites, api }) => {
  let delayed = false;
  let release = () => {};
  const response = new Promise<void>((resolve) => {
    release = resolve;
  });
  await api.route(async (route) => {
    if (
      delayed &&
      route.request().method() === "GET" &&
      new URL(route.request().url()).pathname === "/api/me/favorite-groups"
    )
      await response;
    await route.fallback();
  });
  const stop = api.data.watch.stops.find((item) => item.boardingAllowed)!;
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "저장할 그룹 선택" });
  await dialog.getByRole("button", { name: "새 그룹 만들기", exact: true }).click();
  await dialog.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("통학");
  delayed = true;
  try {
    await dialog.getByRole("button", { name: "만들기", exact: true }).click();
    await expect(dialog.getByText("그룹 목록을 갱신하고 있어요…", { exact: true })).toBeVisible();
    await expect(dialog.getByRole("alert")).toHaveCount(0);
    await expect(dialog.getByRole("button", { name: "저장", exact: true })).toBeDisabled();
    await expect(dialog.getByRole("button", { name: "새 그룹 만들기", exact: true })).toBeDisabled();
    expect(favorites.requests.filter((request) => request.method === "POST")).toHaveLength(1);
  } finally {
    release();
  }
  await expect(dialog.getByRole("checkbox", { name: /통학/ })).toBeChecked();
  await expect(dialog.getByRole("button", { name: "저장", exact: true })).toBeEnabled();
});
