import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures/naver-map";
import { openRouteSearch, pickRoute } from "./utils/route-select";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

async function changeVisualViewport(
  page: Page,
  sample: { height: number; offsetTop: number; scale: number },
  event: "resize" | "scroll" = "resize",
) {
  await page.evaluate(
    ({ height, offsetTop, scale, event }) => {
      const viewport = window.visualViewport!;
      Object.defineProperties(viewport, {
        height: { configurable: true, value: height },
        offsetTop: { configurable: true, value: offsetTop },
        scale: { configurable: true, value: scale },
      });
      viewport.dispatchEvent(new Event(event));
      if (scale === 1 && event === "resize") window.dispatchEvent(new Event("resize"));
    },
    { ...sample, event },
  );
  await page.clock.runFor(32);
}

test("검색 키보드가 닫히는 동안 503 시트를 화면 하단에 유지하고 실제 화면 크기 변경은 반영한다", async ({
  page,
  api,
}) => {
  api.mode.watch = { status: 503, retryAfterSeconds: 30 };
  await page.goto("/");
  await openRouteSearch(page);

  // 키보드가 열린 동안에는 확대 배율이 그대로이고 보이는 영역만 줄어든다.
  await changeVisualViewport(page, { height: 440, offsetTop: 32, scale: 1 });
  await pickRoute(page);
  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await expect(sheet.getByRole("alert")).toHaveText("노선 정보를 불러오지 못했어요");

  async function expectBottomSheet(viewportHeight: number) {
    const box = (await sheet.boundingBox())!;
    expect(Math.abs(box.y + box.height - viewportHeight)).toBeLessThan(1);
    expect(Math.abs(box.height - viewportHeight * 0.6)).toBeLessThan(1);
  }

  await expectBottomSheet(844);
  for (const [height, offsetTop] of [
    [520, 64],
    [680, 16],
    [844, 0],
  ] as const) {
    await changeVisualViewport(page, { height, offsetTop, scale: 1 });
    await expectBottomSheet(844);
  }

  await page.setViewportSize({ width: 390, height: 720 });
  // 시계가 멈춰 있어도 실제 화면 크기를 읽는 resize 처리를 먼저 실행한다.
  await page.evaluate(() => window.dispatchEvent(new Event("resize")));
  await page.clock.runFor(32);
  await expectBottomSheet(720);

  api.mode.watch = "success";
  await sheet.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await expectBottomSheet(720);
});

test("확대하고 화면을 옮기면 503 시트가 보이는 영역의 하단을 따라간다", async ({ page, api }) => {
  api.mode.watch = { status: 503, retryAfterSeconds: 30 };
  await page.goto("/");
  await openRouteSearch(page);
  await pickRoute(page);
  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await expect(sheet.getByRole("alert")).toHaveText("노선 정보를 불러오지 못했어요");

  async function expectSheetBottom(bottom: number) {
    const box = (await sheet.boundingBox())!;
    expect(Math.abs(box.y + box.height - bottom)).toBeLessThan(1);
  }

  await changeVisualViewport(page, { height: 281, offsetTop: 30, scale: 3 });
  await expectSheetBottom(311);

  await changeVisualViewport(page, { height: 281, offsetTop: 180, scale: 3 }, "scroll");
  await expectSheetBottom(461);

  api.mode.watch = "success";
  await sheet.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await expectSheetBottom(461);

  await changeVisualViewport(page, { height: 844, offsetTop: 0, scale: 1 });
  await expectSheetBottom(844);
});
