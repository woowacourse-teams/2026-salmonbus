import { test, expect, defaultGroupId } from "./fixtures/favorites";
import { captureEnabled } from "./utils/capture";
import { checkAtBothWidths } from "./utils/widths";

test("320px 이름 변경 화면은 제목·라벨·입력·정책·저장 순서이며 취소와 오류 재시도를 지원한다", async ({
  page,
  favorites,
  browserName,
}) => {
  await page.setViewportSize({ width: 320, height: 844 });
  favorites.groups[0]!.name = "출퇴근";
  favorites.groups.push({ id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] });
  await page.goto(`/favorites/groups/${defaultGroupId}`);
  await expect(page).toHaveURL(`/favorites/groups/${defaultGroupId}`);
  await expect(page.getByRole("navigation").getByRole("link", { name: "즐겨찾기", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await page.getByRole("button", { name: "그룹 이름 변경", exact: true }).click();
  const heading = page.getByRole("heading", { name: "이름 변경", exact: true });
  const input = page.getByRole("textbox", { name: "변경할 그룹 이름", exact: true });
  const label = page.getByText("변경할 그룹 이름", { exact: true });
  const policy = page.locator("#group-name-help");
  const save = page.getByRole("button", { name: "저장", exact: true });
  await expect(heading).toBeVisible();
  await expect(page.getByRole("heading")).toHaveCount(1);
  await expect(input).toHaveValue("출퇴근");
  await expect(save).toBeDisabled();
  const boxes = await Promise.all([heading, label, input, policy, save].map((element) => element.boundingBox()));
  for (let i = 1; i < boxes.length; i++) expect(boxes[i]!.y).toBeGreaterThan(boxes[i - 1]!.y);
  await expect(policy).toContainText("같은 이름의 그룹은 만들 수 없어요.");
  await input.fill("취소할 이름");
  await page.getByRole("button", { name: "그룹 관리로 돌아가기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "출퇴근", exact: true })).toBeVisible();
  expect(favorites.requests.filter((r) => r.method === "PATCH")).toHaveLength(0);
  await page.getByRole("button", { name: "그룹 이름 변경", exact: true }).click();
  await expect(input).toHaveValue("출퇴근");
  await input.fill("");
  await expect(save).toBeDisabled();
  await input.fill("12345678901");
  await expect(input).toHaveAttribute("aria-invalid", "true");
  await expect(save).toBeDisabled();
  await input.fill("통학");
  await save.click();
  await expect(page.getByRole("alert")).toContainText("이미 사용 중");
  await expect(input).toBeFocused();
  await expect(input).toHaveValue("통학");
  await input.fill("회사 가는 길");
  await expect(page.getByRole("alert")).toBeHidden();
  await expect(save).toBeEnabled();
  await page.evaluate(() => document.fonts.ready);
  await checkAtBothWidths(page, async (width) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (captureEnabled && width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-group-rename.png") });
  });
  await save.click();
  await expect(page.getByRole("heading", { name: "회사 가는 길", exact: true })).toBeVisible();
  expect(favorites.groups[0]!.name).toBe("회사 가는 길");
  await page.getByRole("link", { name: "그룹 목록으로 돌아가기" }).click();
  await page.getByRole("navigation").getByRole("link", { name: "즐겨찾기", exact: true }).click();
  await expect(page.getByRole("tab", { name: "회사 가는 길", exact: true })).toBeVisible();
});

test("즐겨찾기 연필에서 선택한 그룹의 이름을 바꾸고 첫 그룹도 삭제할 수 있다", async ({ page, favorites }) => {
  const schoolId = "10000000-0000-4000-8000-000000000003";
  favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
  await page.goto("/favorites");
  await expect(page.getByRole("link", { name: "그룹 관리", exact: true })).toHaveCount(0);
  await page.getByRole("tab", { name: "통학", exact: true }).click();
  await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
  const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
  await expect(editor.getByRole("button", { name: "통학", exact: true, pressed: true })).toBeVisible();
  await editor.getByRole("button", { name: "그룹 이름 수정", exact: true }).click();
  const name = editor.getByRole("textbox", { name: "변경할 그룹 이름" });
  await expect(name).toBeFocused();
  await name.fill("등교");
  await editor.getByRole("button", { name: "저장", exact: true }).click();
  await expect(editor.getByRole("button", { name: "등교", exact: true, pressed: true })).toBeVisible();
  expect(favorites.groups.find((group) => group.id === schoolId)?.name).toBe("등교");
  await editor.getByRole("button", { name: "즐겨찾기", exact: true }).click();
  await editor.getByRole("button", { name: "그룹 삭제", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "삭제", exact: true }).click();
  await expect(editor.getByRole("button", { name: "등교", exact: true, pressed: true })).toBeVisible();
  await expect(editor.getByRole("button", { name: "그룹 삭제", exact: true })).toHaveCount(0);
  expect(favorites.groups.map((group) => group.id)).toEqual([schoolId]);
  expect(favorites.requests.filter((request) => request.method === "PUT")).toHaveLength(0);
  await editor.getByRole("button", { name: "완료", exact: true }).click();
  await expect(page.getByRole("tab", { name: "등교", exact: true })).toBeVisible();
});
