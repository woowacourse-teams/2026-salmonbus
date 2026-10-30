import type { Page } from "@playwright/test";

// 너비 묶음은 320px로 흐름을 돌고, 배치를 보는 지점에서만 425px로 바꿔 같은 확인을 한 뒤 320px로 되돌린다.
export async function checkAtBothWidths(page: Page, check: (width: number) => Promise<void>) {
  const height = page.viewportSize()?.height ?? 844;
  await check(320);
  await page.setViewportSize({ width: 425, height });
  await check(425);
  await page.setViewportSize({ width: 320, height });
}
