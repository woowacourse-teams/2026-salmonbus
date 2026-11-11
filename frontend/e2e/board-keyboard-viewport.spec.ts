import { test, expect } from "./fixtures/naver-map";
import { openRouteSearch, pickRoute } from "./utils/route-select";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

test("검색 키보드가 닫히는 동안 503 시트를 화면 하단에 유지하고 실제 화면 크기 변경은 반영한다", async ({
  page,
  api,
}) => {
  api.mode.watch = { status: 503, retryAfterSeconds: 30 };
  await page.goto("/");
  await openRouteSearch(page);

  // 실제 키보드를 띄울 수 없는 브라우저에서 iOS의 시각적 뷰포트 축소와 이동을 재현한다.
  async function changeVisualViewport(height: number, offsetTop: number) {
    await page.evaluate(
      ({ height, offsetTop }) => {
        const viewport = window.visualViewport!;
        Object.defineProperties(viewport, {
          height: { configurable: true, value: height },
          offsetTop: { configurable: true, value: offsetTop },
        });
        viewport.dispatchEvent(new Event("resize"));
        viewport.dispatchEvent(new Event("scroll"));
        window.dispatchEvent(new Event("resize"));
      },
      { height, offsetTop },
    );
    await page.clock.runFor(32);
  }

  await changeVisualViewport(440, 32);
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
    await changeVisualViewport(height, offsetTop);
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
