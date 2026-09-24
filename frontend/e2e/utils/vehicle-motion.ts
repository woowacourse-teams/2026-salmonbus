import type { Locator, Page } from "@playwright/test";
import { expect } from "../fixtures/api";

export async function centerY(locator: Locator) {
  return locator.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return bounds.top + bounds.height / 2;
  });
}

async function seekMotion(marker: Locator, progress: number) {
  return marker.evaluate((element, fraction) => {
    const transition = element
      .getAnimations()
      .find((animation) => animation instanceof CSSTransition && animation.transitionProperty === "transform");
    if (!transition?.effect) throw new Error("차량의 transform transition이 실행되어야 합니다");
    const duration = Number(transition.effect.getTiming().duration);
    if (!(duration > 0)) throw new Error("차량 이동 시간이 0보다 커야 합니다");

    // Playwright clock으로 API 폴링과 JS 타이머를 제어합니다.
    // CSS 이동은 실제 transition의 진행 시점을 조절한 뒤,
    // 화면에 표시된 좌표를 측정합니다.
    transition.pause();
    transition.currentTime = duration * fraction;
    const bounds = element.getBoundingClientRect();
    return bounds.top + bounds.height / 2;
  }, progress);
}

export async function expectForwardMotion(page: Page, marker: Locator, from: number, to: number) {
  await expect
    .poll(async () => {
      await page.clock.runFor(20);
      return marker.evaluate((element) => element.getAnimations().filter((a) => a instanceof CSSTransition).length);
    })
    .toBe(1);

  const start = await seekMotion(marker, 0);
  const middle = await seekMotion(marker, 0.5);
  const end = await seekMotion(marker, 1);
  expect(start).toBeGreaterThan(from);
  expect(middle).toBeGreaterThan(start + 1);
  expect(end).toBeGreaterThan(middle + 1);
  expect(end).toBeLessThan(to);
  expect(Math.abs(middle - (start + end) / 2)).toBeLessThan(1);
  return { start, end };
}
