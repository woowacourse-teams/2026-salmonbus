import { test, expect, defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 425, height: 844 }, reducedMotion: "reduce" });
const secondGroupId = "10000000-0000-4000-8000-000000000003";

test("홈은 첫 그룹을 조회하고 탭 변경 시 선택한 그룹만 조회하며 ETA 없는 차량도 표시한다", async ({
  page,
  favorites,
  api,
}) => {
  const up = api.data.board.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  const down = api.data.board.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: up.stopId, direction: "UP" })];
  favorites.groups.push({
    id: secondGroupId,
    name: "통학",
    favorites: [storedFavorite({ routeId, stopId: down.stopId, direction: "DOWN" }, 4)],
  });
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "즐겨찾기", exact: true })).toHaveAttribute("aria-selected", "true");
  const cards = page.getByRole("tabpanel");
  await expect(cards.getByText(up.name, { exact: true })).toBeVisible();
  await expect(
    cards.getByText(
      `${api.data.board.route.directions.find((direction) => direction.id === "UP")!.terminalStopName} 방면`,
      { exact: true },
    ),
  ).toBeVisible();
  await expect(cards.getByText("도착 정보 없음")).toBeVisible();
  await expect(cards.getByText("도착 시 1석 예상해요")).toBeVisible();
  const queries = () => favorites.requests.filter((request) => request.path === "/api/me/favorites");
  expect(queries().every((request) => request.query.includes(defaultGroupId))).toBe(true);
  await page.getByRole("tab", { name: "즐겨찾기", exact: true }).press("ArrowRight");
  await expect(page.getByRole("tab", { name: "통학", exact: true })).toBeFocused();
  await expect(cards.getByText(down.name, { exact: true })).toBeVisible();
  expect(queries().at(-1)?.query).toContain(secondGroupId);
  await page.reload();
  await expect(page.getByRole("tab", { name: "즐겨찾기", exact: true })).toHaveAttribute("aria-selected", "true");
  await cards.getByRole("link").first().click();
  await expect(page).toHaveURL(`/routes/${routeId}?direction=UP&stopId=${up.stopId}`);
});

test("비회원은 로그인 후 원래 판정보드로 복귀하고 다시 눌러 저장한다", async ({ page, auth, favorites, api }) => {
  auth.member = null;
  const stop = api.data.board.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  const destination = `/routes/${routeId}?direction=UP&stopId=${stop.stopId}`;
  await page.goto(destination);
  const star = page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true });
  await star.click();
  await page.getByRole("dialog").getByRole("link", { name: "로그인 / 회원가입", exact: true }).click();
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL(destination);
  expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);
  await star.click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(star).toHaveAttribute("aria-pressed", "true");
  expect(favorites.groups[0]!.favorites).toEqual([
    expect.objectContaining({ routeId, stopId: stop.stopId, direction: "UP" }),
  ]);
});

test("서버의 일괄 저장 실패 시 선택과 기존 등록을 유지하고 같은 선택으로 재시도한다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.board.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  favorites.mutationError = "FAVORITE_LIMIT_EXCEEDED";
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog");
  const choice = dialog.getByRole("checkbox", { name: /즐겨찾기/ });
  await choice.check();
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("2개까지");
  await expect(choice).toBeChecked();
  expect(favorites.groups[0]!.favorites).toHaveLength(0);
  favorites.mutationError = null;
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(1);
});

test("가득 찬 그룹의 기존 선택은 유지하고 한 그룹씩 또는 전체에서 해제한다", async ({ page, favorites, api }) => {
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  const target = { routeId, stopId: first!.stopId, direction: "UP" as const };
  favorites.groups[0]!.favorites = [storedFavorite(target), storedFavorite({ ...target, stopId: second!.stopId }, 5)];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [storedFavorite(target, 4)] });
  await page.goto(`/routes/${routeId}`);
  const star = page.getByRole("button", { name: `${first!.name} 즐겨찾기`, exact: true });
  await star.click();
  let dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("checkbox", { name: /즐겨찾기/ })).toBeChecked();
  await expect(dialog.getByRole("button", { name: "저장", exact: true })).toBeEnabled();
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).uncheck();
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(star).toHaveAttribute("aria-pressed", "true");
  await star.click();
  dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: /통학/ }).uncheck();
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(star).toHaveAttribute("aria-pressed", "false");
  expect(favorites.groups[0]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId]);
  expect(favorites.groups[1]!.favorites).toHaveLength(0);
});

test("기본 그룹도 다른 그룹이 남으면 삭제하고 이름이 바뀌어도 마지막 그룹은 보호한다", async ({ page, favorites }) => {
  await page.goto(`/mypage/groups/${defaultGroupId}`);
  await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("마지막 그룹은 삭제할 수 없어요");
  await page.getByRole("dialog").getByRole("button", { name: "확인", exact: true }).click();
  expect(favorites.requests.some((request) => request.method === "DELETE")).toBe(false);
  await page.getByRole("link", { name: "마이페이지로 돌아가기" }).click();
  await page.getByRole("link", { name: "＋ 새 그룹 만들기", exact: true }).click();
  await page.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("  🚌통학  ");
  await page.getByRole("button", { name: "만들기", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await page.getByRole("link", { name: /🚌통학/ }).click();
  await page.getByRole("button", { name: "그룹 이름 변경", exact: true }).click();
  await page.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("즐겨찾기");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("이미 사용 중");
  await page.getByRole("textbox", { name: "그룹 이름", exact: true }).fill("주말");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("heading", { name: "주말", exact: true })).toBeVisible();
  await page.goto(`/mypage/groups/${defaultGroupId}`);
  await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "삭제", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  expect(favorites.groups.map((group) => group.name)).toEqual(["주말"]);
  await page.getByRole("link", { name: /주말/ }).click();
  await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("마지막 그룹은 삭제할 수 없어요");
});

test("그룹과 즐겨찾기 순서를 키보드로 편집하고 전체 ID 목록으로 저장한다", async ({ page, favorites, api }) => {
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const dialog = page.getByRole("dialog");
  const groupGrip = dialog.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true });
  await groupGrip.focus();
  await groupGrip.press("ArrowDown");
  const favoriteGrip = dialog.getByRole("button", { name: new RegExp(`${first!.name} 순서 변경$`) });
  await favoriteGrip.focus();
  await favoriteGrip.press("ArrowDown");
  await dialog.getByRole("button", { name: "완료", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(favorites.groups.map((group) => group.id)).toEqual([secondGroupId, defaultGroupId]);
  expect(favorites.groups[1]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
  expect(favorites.requests.find((request) => request.path === "/api/me/favorite-groups/order")?.body).toEqual({
    groupIds: [secondGroupId, defaultGroupId],
  });
  await page.reload();
  await expect(page.getByRole("tab", { name: "통학", exact: true })).toHaveAttribute("aria-selected", "true");
});

for (const [status, label] of [
  ["NO_VEHICLE", "도착 예정 차량이 없어요"],
  ["PREPARING", "도착 예보를 준비하고 있어요"],
  ["UNAVAILABLE", "현재 노선에서 이 정류장을 이용할 수 없어요"],
  ["AMBIGUOUS", "승차할 정류장을 다시 확인해 주세요"],
  ["ERROR", "이 즐겨찾기의 정보를 불러오지 못했어요"],
] as const) {
  test(`홈의 ${status} 항목도 유지하고 상태를 안내한다`, async ({ page, favorites, api }) => {
    const stop = api.data.board.stops.find((stop) => stop.boardingAllowed)!;
    const saved = storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction });
    favorites.groups[0]!.favorites = [saved];
    favorites.statuses[saved.id] = status;
    await page.goto("/");
    const panel = page.getByRole("tabpanel");
    await expect(panel.getByText(label, { exact: true })).toBeVisible();
    await expect(panel.getByRole("link")).toContainText(stop.name);
    if (status === "ERROR") {
      favorites.statuses[saved.id] = "READY";
      await panel.getByRole("button", { name: "다시 확인하기", exact: true }).click();
      await expect(panel.getByText("도착 정보 없음")).toBeVisible();
    }
    expect(favorites.groups[0]!.favorites).toHaveLength(1);
  });
}

test("순서 일부가 저장된 뒤 실패하면 저장된 부분을 유지하고 남은 요청만 재시도한다", async ({
  page,
  favorites,
  api,
}) => {
  const [first, second] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  favorites.orderFailureGroupId = defaultGroupId;
  await page.goto("/");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true }).press("ArrowDown");
  await dialog.getByRole("button", { name: new RegExp(`${first!.name} 순서 변경$`) }).press("ArrowDown");
  await dialog.getByRole("button", { name: "완료", exact: true }).click();
  await expect(
    dialog.getByText("일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요."),
  ).toBeVisible();
  await expect(dialog.getByRole("button", { name: "닫기", exact: true })).toBeEnabled();
  expect(favorites.groups.map((group) => group.id)).toEqual([secondGroupId, defaultGroupId]);
  expect(favorites.groups[1]!.favorites[0]!.stopId).toBe(first!.stopId);
  favorites.orderFailureGroupId = null;
  await dialog.getByRole("button", { name: "완료", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(favorites.requests.filter((request) => request.path === "/api/me/favorite-groups/order")).toHaveLength(1);
  expect(favorites.groups[1]!.favorites.map((favorite) => favorite.stopId)).toEqual([second!.stopId, first!.stopId]);
});

test("손잡이를 끌어서 그룹 순서를 바꾸고 저장한다", async ({ page, favorites }) => {
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [] });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집" }).click();
  const dialog = page.getByRole("dialog");
  const first = await dialog.getByRole("button", { name: "즐겨찾기 순서 변경", exact: true }).boundingBox();
  const second = await dialog.getByRole("button", { name: "통학 순서 변경", exact: true }).boundingBox();
  await page.mouse.move(first!.x + first!.width / 2, first!.y + first!.height / 2);
  await page.mouse.down();
  await page.mouse.move(second!.x + second!.width / 2, second!.y + second!.height / 2, { steps: 4 });
  await page.mouse.up();
  await dialog.getByRole("button", { name: "완료", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(favorites.groups.map((group) => group.id)).toEqual([secondGroupId, defaultGroupId]);
});

test("마이페이지에서 한 그룹의 즐겨찾기를 삭제해도 다른 그룹의 같은 대상은 유지한다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.board.stops.find((stop) => stop.boardingAllowed)!;
  const target = { routeId, stopId: stop.stopId, direction: stop.direction };
  favorites.groups[0]!.favorites = [storedFavorite(target)];
  favorites.groups.push({ id: secondGroupId, name: "통학", favorites: [storedFavorite(target, 4)] });
  await page.goto(`/mypage/groups/${defaultGroupId}`);
  await page.getByRole("button", { name: `${stop.name} 삭제`, exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "삭제", exact: true }).click();
  await expect(page.getByText("아직 등록한 즐겨찾기가 없어요.", { exact: true })).toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(0);
  expect(favorites.groups[1]!.favorites).toHaveLength(1);
});

test("이미 가득 찬 그룹에 새 항목을 선택하면 저장을 막는다", async ({ page, favorites, api }) => {
  const [first, second, third] = api.data.board.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
  favorites.groups[0]!.favorites = [
    storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
    storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
  ];
  await page.goto(`/routes/${routeId}`);
  await page.getByRole("button", { name: `${third!.name} 즐겨찾기`, exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
  await expect(dialog.getByRole("button", { name: "저장", exact: true })).toBeDisabled();
  await expect(dialog.getByRole("alert")).toContainText("2개까지");
  expect(favorites.requests.some((request) => request.method === "PUT")).toBe(false);
});

test("갱신 실패 중 도착 예정 시간이 지나면 시간을 감추고 좌석 예보와 즐겨찾기를 유지한다", async ({
  page,
  favorites,
  api,
}) => {
  const stop = api.data.board.stops.find((stop) => stop.boardingAllowed)!;
  favorites.groups[0]!.favorites = [storedFavorite({ routeId, stopId: stop.stopId, direction: stop.direction })];
  await page.goto("/");
  const panel = page.getByRole("tabpanel");
  await expect(panel.getByText("도착 정보 없음", { exact: true })).toHaveCount(1);
  favorites.unavailable = true;
  await page.clock.runFor(481_000);
  await expect(panel.getByText("도착 정보 없음", { exact: true })).toHaveCount(3);
  await expect(panel.getByText("도착 시 7석 예상해요", { exact: true })).toBeVisible();
  expect(favorites.groups[0]!.favorites).toHaveLength(1);
});

test("API 미완성 404는 준비 안내를 보여주고 공개 노선 검색을 유지한다", async ({ page, favorites }) => {
  favorites.unavailable = true;
  await page.goto("/");
  await expect(page.getByRole("alert")).toHaveText("즐겨찾기 기능을 준비 중이에요.");
  await page.getByRole("textbox", { name: /노선/ }).fill("3330");
  await expect(page.getByRole("region", { name: "검색 결과", exact: true })).toBeVisible();
});
