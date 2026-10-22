import { test, expect } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";
import { centerY } from "./utils/vehicle-motion";

for (const width of [320, 425]) {
  test(`${width}px 정류장 별은 접힘 행과 펼친 이름의 중앙에 놓이고 등록 상태를 표시한다`, async ({ page, api }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const stop = api.data.board.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
    stop.name = "긴 정류장 이름도 즐겨찾기 버튼과 겹치지 않아야 해요";
    await page.goto(`/routes/${routeId}`);
    const star = page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true });
    const row = page.locator("li[data-stop-sequence]").filter({ has: star });
    const head = row.locator("button[aria-expanded]");
    const name = head.getByText(stop.name, { exact: true });
    await head.scrollIntoViewIfNeeded();
    await expect(head).toHaveAttribute("aria-expanded", "false");
    await expect.poll(async () => Math.abs((await centerY(star)) - (await centerY(head)))).toBeLessThan(0.75);

    await head.click();
    await expect(head).toHaveAttribute("aria-expanded", "true");
    await expect.poll(async () => Math.abs((await centerY(star)) - (await centerY(name)))).toBeLessThan(0.75);
    const nameBox = (await name.boundingBox())!;
    expect(nameBox.x + nameBox.width).toBeLessThanOrEqual((await star.boundingBox())!.x);
    await expect(star).toHaveAttribute("aria-pressed", "false");
    const outline = await star.locator("img").getAttribute("src");

    await star.click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("checkbox", { name: /즐겨찾기/ }).check();
    await dialog.getByRole("button", { name: "저장", exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(star).toHaveAttribute("aria-pressed", "true");
    await expect(star.locator("img")).not.toHaveAttribute("src", outline!);
    await expect(star.locator("img")).toHaveAttribute("width", "19");
    await expect(head).toHaveAttribute("aria-expanded", "true");
    await expect.poll(async () => Math.abs((await centerY(star)) - (await centerY(name)))).toBeLessThan(0.75);

    await head.click();
    await expect(head).toHaveAttribute("aria-expanded", "false");
    await expect.poll(async () => Math.abs((await centerY(star)) - (await centerY(head)))).toBeLessThan(0.75);
  });
}
