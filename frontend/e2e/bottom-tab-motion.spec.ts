import { test, expect } from "./fixtures/auth";

test("하단 탭은 고정되고 홈과 마이페이지 본문과 선택 색을 전환 모션과 함께 바꾸며 모션 줄이기에서는 애니메이션 없이 바꾼다", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "주요 메뉴" });
  const main = page.getByRole("main");
  const before = (await nav.boundingBox())!;
  for (const reducedMotion of ["no-preference", "reduce"] as const) {
    await test.step(`${reducedMotion}에서 마이페이지와 홈 전환`, async () => {
      await page.emulateMedia({ reducedMotion });
      for (const [name, path] of [
        ["마이페이지", "/mypage"],
        ["홈", "/"],
      ] as const) {
        await nav.getByRole("link", { name, exact: true }).click();
        await expect(page).toHaveURL(path);
        const selected = nav.getByRole("link", { name, exact: true });
        await expect(selected).toHaveAttribute("aria-current", "page");
        if (reducedMotion === "reduce") {
          await expect(main).toHaveCSS("animation-name", "none");
          await expect(selected).toHaveCSS("animation-name", "none");
        } else {
          await expect(main).not.toHaveCSS("animation-name", "none");
          await expect(selected).not.toHaveCSS("animation-name", "none");
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
});
