import type { Locator, Page } from "@playwright/test";
import { expect } from "../fixtures/api";

export async function centerY(locator: Locator) {
  return locator.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return bounds.top + bounds.height / 2;
  });
}

export async function expectForwardMotion(page: Page, marker: Locator, from: number, to: number) {
  // 지도와 목록의 공통 rAF 시계를 진행시키고 실제 표시 좌표를 확인한다.
  await page.clock.runFor(20);
  const start = await centerY(marker);
  await page.clock.fastForward(10_000);
  await page.clock.runFor(20);
  const middle = await centerY(marker);
  await page.clock.fastForward(10_000);
  await page.clock.runFor(20);
  const end = await centerY(marker);
  expect(start).toBeGreaterThan(from);
  expect(middle).toBeGreaterThan(start + 1);
  expect(end).toBeGreaterThan(middle + 1);
  expect(end).toBeLessThan(to);
  expect(Math.abs(middle - (start + end) / 2)).toBeLessThan(1);
  return { start, end };
}
