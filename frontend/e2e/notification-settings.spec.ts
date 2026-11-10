import type { Page } from "@playwright/test";
import { defaultGroupId, storedFavorite } from "./fixtures/favorites";
import { test, expect, storedNotification } from "./fixtures/notifications";
import { routeId } from "./fixtures/mock-data";
import { captureEnabled } from "./utils/capture";
import { expectFocusRing } from "./utils/focus-ring";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

const NOTIFICATION_COMMANDS = [
  "notification.requestPermission",
  "notification.getRegistration",
  "notification.takeOpened",
];
const DEVICE_TOKEN = "a1b2c3d4".repeat(8);

type PermissionStatus = "granted" | "denied" | "undetermined";

interface SimulatedApp {
  capabilities?: string[];
  permission?: PermissionStatus;
  permissionAnswer?: PermissionStatus;
  opened?: { routeId: string; stopId: string; direction: "UP" | "DOWN" } | null;
}

async function openInApp(page: Page, app: SimulatedApp = {}) {
  await page.addInitScript(
    ({ capabilities, permission, permissionAnswer, opened, token }) => {
      let status = permission;
      let pending = opened;
      const reply = (type: string, payload: object) =>
        queueMicrotask(() =>
          window.dispatchEvent(new CustomEvent("salmon:bridge", { detail: { v: 1, type, payload } })),
        );
      window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
      window.ReactNativeWebView = {
        postMessage(raw: string) {
          const { type, payload } = JSON.parse(raw) as { type: string; payload: { requestId: string } };
          if (type === "notification.requestPermission" && status === "undetermined") status = permissionAnswer;
          if (type === "notification.requestPermission" || type === "notification.getRegistration") {
            const device =
              status === "granted" ? { token, environment: "SANDBOX" } : { token: null, environment: null };
            reply("notification.registration", { requestId: payload.requestId, status, ...device });
          } else if (type === "notification.takeOpened") {
            reply("notification.opened", { requestId: payload.requestId, target: pending });
            pending = null;
          }
        },
      };
    },
    {
      capabilities: app.capabilities ?? NOTIFICATION_COMMANDS,
      permission: app.permission ?? "undetermined",
      permissionAnswer: app.permissionAnswer ?? app.permission ?? "undetermined",
      opened: app.opened ?? null,
      token: DEVICE_TOKEN,
    },
  );
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

  for (const inputMethod of ["keyboard", "pointer"] as const) {
    test(`${inputMethod}로 연 알림 창 제목은 입력 방식에 맞는 포커스 표시를 보인다`, async ({ page }) => {
      await openInApp(page);
      await page.goto(`/mypage/groups/${defaultGroupId}`);
      const trigger = notificationSwitch(page);
      await expect(trigger).toBeVisible();
      if (inputMethod === "keyboard") {
        await trigger.focus();
        await page.keyboard.press("Space");
      } else {
        await trigger.click();
      }
      const sheet = page.getByRole("dialog", { name: "출발 알림" });
      const title = sheet.getByRole("heading", { name: "출발 알림", exact: true });
      await expect(title).toBeFocused();
      if (captureEnabled) await page.screenshot({ path: test.info().outputPath(`dialog-title-${inputMethod}.png`) });
      await expectFocusRing(title, inputMethod === "keyboard" ? "shown" : "hidden");
      if (inputMethod === "keyboard") {
        await expect(title).toHaveCSS("outline-width", "3px");
        await expect(title).toHaveCSS("outline-offset", "4px");
      }
      await page.keyboard.press("Escape");
      await expect(sheet).toBeHidden();
      await expect(trigger).toBeFocused();
    });
  }

  test("브라우저에서는 알림 줄을 숨기고 설정을 읽지 않는다", async ({ page, notifications }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(page.getByText(stopName, { exact: true })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(0);
    expect(notifications.requests).toEqual([]);
  });

  test("알림 권한 명령을 모르는 옛 앱에서는 알림 줄을 숨긴다", async ({ page, notifications }) => {
    await openInApp(page, { capabilities: ["haptic.success", "settings.open"] });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(page.getByText(stopName, { exact: true })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(0);
    expect(notifications.requests).toEqual([]);
  });

  test("알림을 허용한 앱이어도 로그아웃 상태면 알림 줄이 없고 알림 요청도 없다", async ({
    page,
    auth,
    notifications,
  }) => {
    auth.member = null;
    await openInApp(page, { permission: "granted" });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(page.getByRole("link", { name: "로그인 / 회원가입" })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(0);
    expect(notifications.requests).toEqual([]);
  });

  test("알림 창을 열면 포커스가 창 안에 있지만 입력칸에는 없다", async ({ page }) => {
    await openInApp(page);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(notificationSwitch(page)).toBeVisible();
    await page.evaluate(() => {
      const focusedTags: string[] = [];
      document.addEventListener("focusin", (event) => {
        if (event.target instanceof Element && event.target.closest("dialog")) focusedTags.push(event.target.tagName);
      });
      Object.assign(window, { focusedTags });
    });
    await notificationSwitch(page).click();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });
    await expect(sheet).toBeVisible();
    const focus = await page.evaluate(() => {
      const active = document.activeElement;
      return {
        insideDialog: active?.closest("dialog[open]") !== null,
        focusedTags: Reflect.get(window, "focusedTags") as string[],
      };
    });
    expect(focus.insideDialog).toBe(true);
    expect(focus.focusedTags.length).toBeGreaterThan(0);
    expect(focus.focusedTags.filter((tag) => ["INPUT", "TEXTAREA", "SELECT"].includes(tag))).toEqual([]);
  });

  test("저장된 알림은 켜진 채로 보이고, 시각과 요일을 바꿔 저장하면 바꾼 값을 PUT으로 보내고 요약이 바뀐다", async ({
    page,
    notifications,
  }) => {
    notifications.settings = [storedNotification(target, "07:40", ["MON", "TUE", "WED", "THU", "FRI"])];
    await openInApp(page);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(notificationSwitch(page)).toBeChecked();
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
    await openInApp(page);
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
    await openInApp(page);
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await notificationSwitch(page).click();
    await expect(notificationSwitch(page)).not.toBeChecked();
    await expect(page.getByRole("button", { name: "평일 07:40", exact: true })).toBeHidden();
    expect(notifications.requests.filter((request) => request.method !== "GET")).toEqual([
      { path: `/api/me/notification-settings/${setting.id}`, method: "DELETE", body: null },
    ]);
    expect(notifications.settings).toEqual([]);
  });

  test("권한 창에서 고르지 않고 닫으면 스위치가 꺼진 채로 남고 알림 API를 부르지 않는다", async ({
    page,
    notifications,
  }) => {
    await openInApp(page, { permissionAnswer: "undetermined" });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await expect(notificationSwitch(page)).not.toBeChecked();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });

    await test.step("창에서 취소하면 스위치가 꺼진 채로 남는다", async () => {
      await notificationSwitch(page).click();
      await sheet.getByRole("button", { name: "취소", exact: true }).click();
      await expect(sheet).toBeHidden();
      await expect(notificationSwitch(page)).not.toBeChecked();
    });

    await test.step("저장했지만 권한을 고르지 않으면 꺼진 채로 남고 안내도 알림 API도 없다", async () => {
      await notificationSwitch(page).click();
      await sheet.getByRole("button", { name: "저장", exact: true }).click();
      await expect(sheet).toBeHidden();
      await expect(notificationSwitch(page)).not.toBeChecked();
      await expect(page.getByText("iPhone 설정에서 연어버스의 알림을 허용해 주세요.")).toHaveCount(0);
      expect(notifications.requests).toEqual([{ path: "/api/me/notification-settings", method: "GET", body: null }]);
    });
  });

  test("알림을 켜고 권한을 허용하면 기기를 등록한 뒤 설정을 저장한다", async ({ page, notifications }) => {
    await openInApp(page, { permissionAnswer: "granted" });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await notificationSwitch(page).click();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });
    await sheet.getByRole("button", { name: "저장", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(notificationSwitch(page)).toBeChecked();
    await expect(page.getByRole("button", { name: "평일 07:30", exact: true })).toBeVisible();
    expect(notifications.requests.filter((request) => request.method !== "GET")).toEqual([
      { path: "/api/me/push-devices", method: "PUT", body: { token: DEVICE_TOKEN, environment: "SANDBOX" } },
      {
        path: "/api/me/notification-settings",
        method: "PUT",
        body: { ...target, time: "07:30", weekdays: ["MON", "TUE", "WED", "THU", "FRI"] },
      },
    ]);
  });

  test("알림을 켜고 권한을 거절하면 설정 안내를 보이고 알림 API를 부르지 않는다", async ({ page, notifications }) => {
    await openInApp(page, { permissionAnswer: "denied" });
    await page.goto(`/mypage/groups/${defaultGroupId}`);
    await notificationSwitch(page).click();
    const sheet = page.getByRole("dialog", { name: "출발 알림" });
    await sheet.getByRole("button", { name: "저장", exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(page.getByText("iPhone 설정에서 연어버스의 알림을 허용해 주세요.")).toBeVisible();
    await expect(notificationSwitch(page)).not.toBeChecked();
    expect(notifications.requests).toEqual([{ path: "/api/me/notification-settings", method: "GET", body: null }]);
  });
});

const REGISTER_PATH = "/api/me/push-devices";
const UNREGISTER_PATH = "/api/me/push-devices/unregister";

function recordDeviceRequests(page: Page) {
  const sent: { request: string; body: unknown }[] = [];
  const urls: string[] = [];
  page.on("request", (request) => {
    urls.push(request.url());
    const path = new URL(request.url()).pathname;
    if (path === REGISTER_PATH || path === UNREGISTER_PATH || path === "/api/auth/logout") {
      sent.push({ request: `${request.method()} ${path}`, body: request.postData() ? request.postDataJSON() : null });
    }
  });
  return { sent, urls };
}

const registration = { request: `PUT ${REGISTER_PATH}`, body: { token: DEVICE_TOKEN, environment: "SANDBOX" } };
const unregistration = { request: `POST ${UNREGISTER_PATH}`, body: { token: DEVICE_TOKEN } };
const logout = { request: "POST /api/auth/logout", body: null };

test("허용된 앱을 열면 기기를 한 번 등록하고, 로그아웃하면 그 token을 먼저 지운 뒤 로그아웃한다", async ({ page }) => {
  await openInApp(page, { permission: "granted" });
  const { sent, urls } = recordDeviceRequests(page);
  const registered = page.waitForResponse(
    (response) => response.request().method() === "PUT" && new URL(response.url()).pathname === REGISTER_PATH,
  );
  await page.goto("/mypage");
  await registered;
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "로그아웃", exact: true }).click();
  await expect(page).toHaveURL("/");
  expect(sent).toEqual([registration, unregistration, logout]);
  expect(urls.filter((url) => url.includes(DEVICE_TOKEN))).toEqual([]);
});

test("기기 등록 응답이 오기 전에 로그아웃하면 등록이 끝난 뒤에 token을 지우고 로그아웃한다", async ({ page }) => {
  await openInApp(page, { permission: "granted" });
  const { sent } = recordDeviceRequests(page);
  let releaseRegistration: () => void = () => {};
  const registrationHeld = new Promise<void>((held) => {
    void page.route(`**${REGISTER_PATH}`, async (route) => {
      if (route.request().method() !== "PUT") return route.fallback();
      held();
      await new Promise<void>((release) => (releaseRegistration = release));
      sent.push({ request: "PUT 응답", body: null });
      await route.fallback();
    });
  });
  await page.goto("/mypage");
  await registrationHeld;
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "로그아웃", exact: true }).click();
  const early = await page
    .waitForRequest(
      (request) => request.url().endsWith(UNREGISTER_PATH) || request.url().endsWith("/api/auth/logout"),
      { timeout: 1_000 },
    )
    .catch(() => null);
  expect(early).toBeNull();
  releaseRegistration();
  await expect(page).toHaveURL("/");
  expect(sent).toEqual([registration, { request: "PUT 응답", body: null }, unregistration, logout]);
});

test("알림을 눌러 앱을 열면 로그인하지 않아도 그 정류장의 판정 보드를 연다", async ({ page, auth, api }) => {
  auth.member = null;
  const stop = api.data.watch.stops.find((candidate) => candidate.direction === "UP" && candidate.boardingAllowed)!;
  await openInApp(page, { opened: { routeId, stopId: stop.stopId, direction: "UP" } });
  await page.goto("/");
  await expect(page).toHaveURL(`/routes/${routeId}?direction=UP&stopId=${stop.stopId}`);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
});
