import { test, expect, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

for (const path of ["/", "/mypage"]) {
  for (const direction of ["down", "up"] as const) {
    for (const reducedMotion of ["no-preference", "reduce"] as const) {
      test(`${path} ${direction} ${reducedMotion} 카드 정렬은 놓는 순간 되돌아가지 않는다`, async ({
        page,
        favorites,
        api,
      }) => {
        await page.setViewportSize({ width: 425, height: 844 });
        await page.emulateMedia({ reducedMotion });
        const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
        favorites.groups[0]!.favorites = [
          storedFavorite({ routeId, stopId: first!.stopId, direction: "UP" }),
          storedFavorite({ routeId, stopId: second!.stopId, direction: "UP" }, 5),
        ];
        await page.goto(path);
        await page.getByRole("button", { name: "그룹과 즐겨찾기 순서 편집", exact: true }).click();
        const editor = page.getByRole("region", { name: "즐겨찾기 편집", exact: true });
        const list = editor.getByRole("list", { name: "즐겨찾기 순서", exact: true });
        const cards = list.getByRole("listitem");
        await expect(cards.first().getByText(first!.name, { exact: true })).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        await editor.evaluate((element) =>
          Promise.all(element.getAnimations({ subtree: true }).map((animation) => animation.finished)),
        );
        await page.clock.resume();
        const from = direction === "down" ? 0 : 1;
        const grip = (await cards
          .nth(from)
          .getByRole("button", { name: /순서 변경$/ })
          .boundingBox())!;
        const firstBox = (await cards.first().boundingBox())!;
        const secondBox = (await cards.nth(1).boundingBox())!;
        const start = { x: grip.x + grip.width / 2, y: grip.y + grip.height / 2 };
        await page.mouse.move(start.x, start.y);
        await page.mouse.down();
        await page.mouse.move(start.x, start.y + (secondBox.y - firstBox.y) * (direction === "down" ? 1 : -1));
        // 주변 카드의 180ms 전환이 진행되는 도중 놓는다.
        await list.evaluate(
          () =>
            new Promise<void>((resolve) => {
              let remaining = 6;
              const frame = () => {
                if (--remaining > 0) requestAnimationFrame(frame);
                else resolve();
              };
              requestAnimationFrame(frame);
            }),
        );
        const recording = await list.evaluateHandle((element) => {
          const rows = Array.from(element.children);
          return {
            frames: (async () => {
              const samples: { positions: number[]; scroll: number }[] = [];
              for (let frame = 0; frame < 24; frame++) {
                samples.push({ positions: rows.map((row) => row.getBoundingClientRect().y), scroll: scrollY });
                await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
              }
              return samples;
            })(),
          };
        });
        await page.mouse.up();
        const samples = await recording.evaluate(({ frames }) => frames);
        await recording.dispose();
        const sibling = 1 - from;
        for (let frame = 1; frame < samples.length; frame++) {
          const previous = samples[frame - 1]!;
          const current = samples[frame]!;
          const backward =
            (current.positions[sibling]! - previous.positions[sibling]!) * (direction === "down" ? 1 : -1);
          expect(backward, `frame ${frame}: ${JSON.stringify(samples)}`).toBeLessThanOrEqual(1);
          expect(current.scroll).toBe(previous.scroll);
        }
        if (reducedMotion === "reduce") {
          expect(await list.evaluate((element) => element.getAnimations({ subtree: true }).length)).toBe(0);
        }
        await expect(cards.first()).toContainText(second!.name);
        await editor.getByRole("button", { name: "완료", exact: true }).click();
        await expect(editor).toHaveCount(0);
        expect(favorites.groups[0]!.favorites.map((favorite) => favorite.stopId)).toEqual([
          second!.stopId,
          first!.stopId,
        ]);
      });
    }
  }
}
