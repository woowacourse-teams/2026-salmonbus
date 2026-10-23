import { test, expect, defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { routeId } from "./fixtures/mock-data";

const schoolId = "10000000-0000-4000-8000-000000000003";

for (const width of [320, 425]) {
  for (const kind of ["favorite", "group"] as const) {
    test(`${width}px 그룹 상세의 ${kind} 삭제 모달은 대상을 표시하고 취소·실패·재시도를 지원한다`, async ({
      page,
      favorites,
      api,
      browserName,
    }) => {
      await page.setViewportSize({ width, height: 844 });
      const [first, second] = api.data.watch.stops.filter((stop) => stop.direction === "UP" && stop.boardingAllowed);
      first!.name = "야탑역.종합버스터미널";
      const target = { routeId, stopId: first!.stopId, direction: first!.direction };
      favorites.groups[0]!.name = "출퇴근";
      favorites.groups[0]!.favorites = [storedFavorite(target)];
      favorites.groups.push({
        id: schoolId,
        name: "통학",
        favorites: [
          storedFavorite(target, 4),
          storedFavorite({ routeId, stopId: second!.stopId, direction: second!.direction }, 5),
        ],
      });
      await page.goto(`/mypage/groups/${kind === "group" ? schoolId : defaultGroupId}`);
      await expect(page.getByText(first!.name, { exact: true })).toBeVisible();
      const trigger = page.getByRole("button", {
        name: kind === "group" ? "그룹 삭제" : `${first!.name} 삭제`,
        exact: true,
      });
      await trigger.click();
      const dialog = page.getByRole("dialog", {
        name: kind === "group" ? "통학을 삭제할까요?" : "정말 삭제 하시겠습니까?",
        exact: true,
      });
      await expect(dialog).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      const box = await dialog.boundingBox();
      expect(box!.width).toBe(width === 425 ? 356 : 256);
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      await expect(dialog).toHaveCSS("background-color", "rgb(255, 255, 255)");
      await expect(dialog).toHaveCSS("border-radius", "14px");
      if (kind === "group") {
        await expect(dialog).toContainText(
          "이 그룹과 즐겨찾기 2개가 함께 삭제돼요. 다른 그룹에 저장된 즐겨찾기는 그대로 유지돼요.",
        );
      } else {
        await expect(dialog.getByText(first!.name, { exact: true })).toBeVisible();
        await expect(dialog.getByText(api.data.watch.route.displayName, { exact: true })).toBeVisible();
        await expect(dialog).toContainText(
          `${api.data.watch.route.directions.find((d) => d.id === first!.direction)!.terminalStopName} 방면`,
        );
      }
      const remove = dialog.getByRole("button", { name: "삭제", exact: true });
      const cancel = dialog.getByRole("button", { name: "취소", exact: true });
      expect((await remove.boundingBox())!.x).toBeLessThan((await cancel.boundingBox())!.x);
      expect((await remove.boundingBox())!.height).toBeCloseTo(48, 2);
      expect((await cancel.boundingBox())!.height).toBeCloseTo(48, 2);
      if (width === 425 && browserName === "chromium")
        await page.screenshot({ path: `/private/tmp/salmonbus-group-delete-${kind}.png` });
      await cancel.click();
      await expect(dialog).toHaveCount(0);
      await expect(trigger).toBeFocused();
      expect(favorites.requests.filter((r) => r.method === "DELETE")).toHaveLength(0);
      await trigger.click();
      await page.keyboard.press("Escape");
      await expect(dialog).toHaveCount(0);
      await expect(trigger).toBeFocused();
      favorites.mutationError = "INTERNAL_ERROR";
      await trigger.click();
      await remove.click();
      await expect(dialog.getByRole("alert")).toBeVisible();
      expect(favorites.groups).toHaveLength(2);
      expect(favorites.groups[0]!.favorites).toHaveLength(1);
      favorites.mutationError = null;
      await remove.click();
      await expect(dialog).toHaveCount(0);
      if (kind === "group") {
        await expect(page).toHaveURL("/mypage");
        expect(favorites.groups.map((g) => g.id)).toEqual([defaultGroupId]);
        expect(favorites.groups[0]!.favorites).toHaveLength(1);
      } else {
        await expect(page.getByText("아직 등록한 정류장이 없어요", { exact: true })).toBeVisible();
        expect(favorites.groups[0]!.favorites).toHaveLength(0);
        expect(favorites.groups[1]!.favorites).toHaveLength(2);
        await expect(page.getByRole("button", { name: "그룹 삭제", exact: true })).toBeFocused();
      }
    });
  }
}

for (const width of [320, 425]) {
  for (const count of [1, 2]) {
    test(`${width}px 대표 그룹은 ${count}개 그룹일 때 이름과 무관하게 삭제를 막는다`, async ({
      page,
      favorites,
      browserName,
    }) => {
      await page.setViewportSize({ width, height: 844 });
      favorites.groups[0]!.name = "이름을바꾼대표그룹";
      if (count === 2) favorites.groups.push({ id: schoolId, name: "통학", favorites: [] });
      await page.goto("/mypage");
      await page.getByRole("link", { name: /이름을바꾼대표그룹 · 대표 그룹/ }).click();
      const header = page.getByRole("heading", { name: "이름을바꾼대표그룹", exact: true }).locator("..");
      await expect(header.getByText("대표", { exact: true })).toHaveCSS("background-color", "rgb(255, 234, 228)");
      await page.getByRole("button", { name: "그룹 삭제", exact: true }).click();
      const dialog = page.getByRole("dialog", { name: "대표 그룹은 삭제할 수 없어요" });
      await expect(dialog).toContainText("첫 번째로 설정된 대표 그룹은 삭제할 수 없어요.");
      await expect(dialog.getByRole("button")).toHaveCount(1);
      await expect(dialog.getByRole("button", { name: "삭제", exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      if (width === 425 && count === 2 && browserName === "chromium")
        await page.screenshot({ path: "/private/tmp/salmonbus-representative.png" });
      await dialog.getByRole("button", { name: "확인", exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(page.getByRole("button", { name: "그룹 삭제", exact: true })).toBeFocused();
      expect(favorites.requests.filter((r) => r.method === "DELETE")).toHaveLength(0);
    });
  }
}
