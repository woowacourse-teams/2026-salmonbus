import type { Page } from "@playwright/test";
import { defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { test, expect, storedNotification } from "./fixtures/notifications";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

const NOTIFICATION_CAPABILITY = "notification.requestPermission";

/** 앱 WebView 흉내. capabilities에 알림 권한 명령이 없으면 옛 앱이다. */
async function openInApp(page: Page, capabilities: string[]) {
  await page.addInitScript((capabilities) => {
    window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
    window.ReactNativeWebView = { postMessage() {} };
  }, capabilities);
}

test.describe("그룹 편집 화면의 알림 줄", () => {
  let stopName: string;
  let target: { routeId: string; stopId: string; direction: "UP" | "DOWN" };

  test.beforeEach(({ api, favorites }) => {
    const stop = api.data.watch.stops.find((candidate) => candidate.direction === "UP" && candidate.boardingAllowed)!;
    stopName = stop.name;
    target = { routeId, stopId: stop.stopId, direction: stop.direction };
    favorites.groups[0]!.favorites = [storedFavorite(target)];
  });

  const notificationSwitch = (page: Page) => page.getByRole("switch", { name: `${stopName} 출발 알림` });

  test("브라우저에서는 알림 줄을 숨기고 설정을 읽지 않는다", async ({ page, notifications }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(page.getByText(stopName, { exact: true })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(0);
    expect(notifications.requests).toEqual([]);
  });

  test("알림 권한 명령을 모르는 옛 앱에서는 알림 줄을 숨긴다", async ({ page, notifications }) => {
    await openInApp(page, ["haptic.success", "settings.open"]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(page.getByText(stopName, { exact: true })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(0);
    expect(notifications.requests).toEqual([]);
  });

  test("저장된 알림이 있으면 켜진 스위치와 요약을 보인다", async ({ page, notifications }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    await openInApp(page, [NOTIFICATION_CAPABILITY]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(notificationSwitch(page)).toBeChecked();
    await expect(page.getByRole("button", { name: "평일 07:40", exact: true })).toBeVisible();
  });

  test("시각과 요일을 바꿔 저장하면 바꾼 값을 PUT으로 보내고 요약이 바뀐다", async ({ page, notifications }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    await openInApp(page, [NOTIFICATION_CAPABILITY]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await page.getByRole("button", { name: "평일 07:40", exact: true }).click();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });
    await expect(sheet.getByLabel("시각")).toHaveValue("07:40");
    await sheet.getByLabel("시각").fill("08:10");
    await sheet.getByRole("button", { name: "금요일" }).click();
    await sheet.getByRole("button", { name: "토요일" }).click();
    await expect(sheet.getByRole("button", { name: "토요일" })).toHaveAttribute("aria-pressed", "true");
    await sheet.getByRole("button", { name: "저장", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(page.getByRole("button", { name: "월 화 수 목 토 08:10", exact: true })).toBeVisible();
    expect(notifications.requests.filter((request) => request.method !== "GET")).toEqual([
      {
        path: "/api/me/notification-settings",
        method: "PUT",
        body: { ...target, time: "08:10", weekdays: ["MON", "TUE", "WED", "THU", "SAT"] },
      },
    ]);
  });

  test("즐겨찾기가 아닌 대상이라는 답이면 창 안에 문구를 보이고 이전 값을 지킨다", async ({ page, notifications }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    notifications.mutationError = { status: 422, code: "NOTIFICATION_TARGET_NOT_FAVORITE" };
    await openInApp(page, [NOTIFICATION_CAPABILITY]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await page.getByRole("button", { name: "평일 07:40", exact: true }).click();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });
    await sheet.getByLabel("시각").fill("08:10");
    await sheet.getByRole("button", { name: "저장", exact: true }).click();
    await expect(sheet.getByRole("alert")).toHaveText(
      "즐겨찾기에 저장된 정류장만 알림을 받을 수 있어요. 목록을 다시 확인해 주세요.",
    );
    await expect(sheet.getByLabel("시각")).toHaveValue("08:10");
    await sheet.getByRole("button", { name: "취소", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(page.getByRole("button", { name: "평일 07:40", exact: true })).toBeVisible();
    await expect(notificationSwitch(page)).toBeChecked();
  });

  test("켜진 스위치를 끄면 바로 DELETE를 보내고 꺼진다", async ({ page, notifications }) => {
    const setting = storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"]);
    notifications.settings = [setting];
    await openInApp(page, [NOTIFICATION_CAPABILITY]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await notificationSwitch(page).click();
    await expect(notificationSwitch(page)).not.toBeChecked();
    await expect(page.getByRole("button", { name: "평일 07:40", exact: true })).toBeHidden();
    expect(notifications.requests.filter((request) => request.method !== "GET")).toEqual([
      { path: `/api/me/notification-settings/${setting.id}`, method: "DELETE", body: null },
    ]);
    expect(notifications.settings).toEqual([]);
  });

  test("앱 답이 오기 전의 기본 응답으로는 스위치가 꺼진 채로 남고 알림 API를 부르지 않는다", async ({
    page,
    notifications,
  }) => {
    await openInApp(page, [NOTIFICATION_CAPABILITY]);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(notificationSwitch(page)).not.toBeChecked();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });

    await notificationSwitch(page).click();
    await expect(sheet.getByLabel("시각")).toHaveValue("07:30");
    for (const weekday of ["월요일", "화요일", "수요일", "목요일", "금요일"])
      await expect(sheet.getByRole("button", { name: weekday })).toHaveAttribute("aria-pressed", "true");
    for (const weekday of ["토요일", "일요일"])
      await expect(sheet.getByRole("button", { name: weekday })).toHaveAttribute("aria-pressed", "false");
    await sheet.getByRole("button", { name: "취소", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(notificationSwitch(page)).not.toBeChecked();

    await notificationSwitch(page).click();
    await sheet.getByRole("button", { name: "저장", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(notificationSwitch(page)).not.toBeChecked();
    await expect(page.getByText("iPhone 설정에서 연어버스의 알림을 허용해 주세요.")).toHaveCount(0);
    expect(notifications.requests).toEqual([{ path: "/api/me/notification-settings", method: "GET", body: null }]);
  });
});
