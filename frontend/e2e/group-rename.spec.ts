import { test, expect, defaultGroupId } from "./fixtures/favorites";

for (const width of [320, 425]) {
  test(`${width}px 이름 변경 화면은 제목·라벨·입력·정책·저장 순서이며 취소와 오류 재시도를 지원한다`, async ({
    page,
    favorites,
    browserName,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    favorites.groups[0]!.name = "출퇴근";
    favorites.groups.push({ id: "10000000-0000-4000-8000-000000000003", name: "통학", favorites: [] });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
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
    expect(boxes[2]!.width).toBe(width === 425 ? 356 : 256);
    expect(boxes[2]!.height).toBe(56);
    expect(boxes[4]!.height).toBe(50);
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
    await expect(input).toHaveValue("통학");
    await input.fill("회사 가는 길");
    await expect(page.getByRole("alert")).toBeHidden();
    await expect(save).toBeEnabled();
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 425 && browserName === "chromium")
      await page.screenshot({ path: test.info().outputPath("salmonbus-group-rename.png") });
    await save.click();
    await expect(page.getByRole("heading", { name: "회사 가는 길", exact: true })).toBeVisible();
    expect(favorites.groups[0]!.name).toBe("회사 가는 길");
  });
}
