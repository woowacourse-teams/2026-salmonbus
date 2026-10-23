import { test, expect } from "./fixtures/auth";

for (const reducedMotion of ["no-preference", "reduce"] as const) {
  test(`하단 탭은 고정되고 홈·마이페이지 본문과 선택 색이 부드럽게 전환된다 (${reducedMotion})`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion });
    await page.goto("/");
    const nav = page.getByRole("navigation", { name: "주요 메뉴" });
    const before = (await nav.boundingBox())!;
    for (const [name, path] of [
      ["마이페이지", "/mypage"],
      ["홈", "/"],
    ] as const) {
      await nav.getByRole("link", { name, exact: true }).click();
      await expect(page).toHaveURL(path);
      const selected = nav.getByRole("link", { name, exact: true });
      await expect(selected).toHaveAttribute("aria-current", "page");
      const main = page.getByRole("main");
      if (reducedMotion === "reduce") {
        await expect(main).toHaveCSS("animation-name", "none");
        await expect(selected).toHaveCSS("animation-name", "none");
      } else {
        const middle = await main.evaluate((element) => {
          const animation = element.getAnimations().find((a) => a instanceof CSSAnimation);
          if (!animation) throw new Error("화면 진입 애니메이션이 필요합니다.");
          animation.pause();
          animation.currentTime = Number(animation.effect!.getTiming().duration) / 2;
          const opacity = Number(getComputedStyle(element).opacity);
          animation.finish();
          return opacity;
        });
        expect(middle).toBeGreaterThan(0.6);
        expect(middle).toBeLessThan(1);
        const colors = await selected.evaluate((element) => {
          const animation = element.getAnimations().find((a) => a instanceof CSSAnimation);
          if (!animation) throw new Error("탭 선택 색 전환이 필요합니다.");
          animation.pause();
          animation.currentTime = 0;
          const from = getComputedStyle(element).color;
          animation.currentTime = Number(animation.effect!.getTiming().duration) / 2;
          const middle = getComputedStyle(element).color;
          animation.finish();
          return { from, middle, to: getComputedStyle(element).color };
        });
        expect(colors.middle).not.toBe(colors.from);
        expect(colors.middle).not.toBe(colors.to);
      }
      const after = (await nav.boundingBox())!;
      for (const key of ["x", "y", "width", "height"] as const) expect(after[key]).toBeCloseTo(before[key], 1);
      await expect(selected).toHaveCSS("color", "rgb(232, 82, 63)");
      await expect(main).toHaveCSS("opacity", "1");
      await expect(
        path === "/"
          ? page.getByRole("region", { name: "내 정류장을 저장해 보세요" })
          : page.getByRole("heading", { name: "연어버스에 합류 하세요!" }),
      ).toBeVisible();
    }
  });
}
