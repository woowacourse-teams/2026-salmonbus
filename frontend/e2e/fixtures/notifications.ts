import { test as favoritesTest, expect } from "./favorites";
import type { FavoriteTarget } from "../../src/shared/api/favorites.types";
import type { NotificationSetting } from "../../src/shared/api/notifications.types";

const settingId = (value: number) => `20000000-0000-4000-8000-${String(value).padStart(12, "0")}`;

interface NotificationsMock {
  settings: NotificationSetting[];
  /** 값이 있으면 GET이 아닌 알림 요청은 모두 이 오류로 답한다. */
  mutationError: { status: number; code: string } | null;
  requests: { path: string; method: string; body: Record<string, unknown> | null }[];
}

/** 테크 스펙 v4의 알림 API 표(push-devices, notification-settings)를 흉내 낸다. */
export const test = favoritesTest.extend<{ notifications: NotificationsMock }>({
  notifications: [
    async ({ auth, api }, use) => {
      const notifications: NotificationsMock = { settings: [], mutationError: null, requests: [] };
      let sequence = 100;
      await api.route(async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (!path.startsWith("/api/me/notification-settings") && !path.startsWith("/api/me/push-devices/")) {
          await route.fallback();
          return;
        }
        const method = request.method();
        const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : null;
        notifications.requests.push({ path, method, body });
        const error = (status: number, code: string) =>
          route.fulfill({
            status,
            json: { code, message: code, requestId: "e2e-notifications" },
            headers: { "Cache-Control": "no-store" },
          });
        if (!auth.member) {
          await error(401, "UNAUTHENTICATED");
          return;
        }
        if (method !== "GET" && request.headers()["x-xsrf-token"] !== "e2e-csrf") {
          await error(400, "CSRF_REJECTED");
          return;
        }
        if (method !== "GET" && notifications.mutationError) {
          await error(notifications.mutationError.status, notifications.mutationError.code);
          return;
        }
        if (path.startsWith("/api/me/push-devices/") && (method === "PUT" || method === "DELETE")) {
          await route.fulfill({ status: 204 });
        } else if (path === "/api/me/notification-settings" && method === "GET") {
          await route.fulfill({ json: { settings: notifications.settings }, headers: { "Cache-Control": "no-store" } });
        } else if (path === "/api/me/notification-settings" && method === "PUT") {
          const { routeId, stopId, direction, time, weekdays } = body as unknown as Omit<NotificationSetting, "id">;
          const target: FavoriteTarget = { routeId, stopId, direction };
          const existing = notifications.settings.find(
            (setting) =>
              setting.routeId === target.routeId &&
              setting.stopId === target.stopId &&
              setting.direction === target.direction,
          );
          const saved = { id: existing?.id ?? settingId(sequence++), ...target, time, weekdays };
          notifications.settings = [...notifications.settings.filter((setting) => setting !== existing), saved];
          await route.fulfill({ json: saved, headers: { "Cache-Control": "no-store" } });
        } else if (path.startsWith("/api/me/notification-settings/") && method === "DELETE") {
          const id = path.split("/")[4];
          if (!notifications.settings.some((setting) => setting.id === id)) {
            await error(404, "NOTIFICATION_SETTING_NOT_FOUND");
            return;
          }
          notifications.settings = notifications.settings.filter((setting) => setting.id !== id);
          await route.fulfill({ status: 204 });
        } else {
          await error(400, "INVALID_REQUEST");
        }
      });
      await use(notifications);
    },
    { auto: true },
  ],
});

export { expect };
export function storedNotification(target: FavoriteTarget, time: string, weekdays: NotificationSetting["weekdays"]) {
  return { id: settingId(1), ...target, time, weekdays };
}
