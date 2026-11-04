import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import {
  deleteNotificationSetting,
  fetchNotificationSettings,
  registerPushDevice,
  saveNotificationSetting,
  unregisterPushDevice,
} from "./notifications.api";
import type { NotificationSetting } from "./notifications.types";

const originalFetch = global.fetch;
const DEVICE_TOKEN = "a1b2c3d4".repeat(8);
const target = { routeId: "200000001", stopId: "200000011", direction: "UP" } as const;
const setting = {
  id: "20000000-0000-4000-8000-000000000001",
  ...target,
  time: "07:40",
  weekdays: ["MON", "TUE", "WED", "THU", "FRI"],
} satisfies NotificationSetting;

function respondWith(response: () => Response) {
  const fetchMock = jest.fn<typeof fetch>().mockImplementation(async () => response());
  global.fetch = fetchMock;
  return fetchMock;
}

beforeEach(() => {
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { cookie: "__Host-XSRF-TOKEN=csrf-test" },
  });
});
afterEach(() => {
  global.fetch = originalFetch;
  Reflect.deleteProperty(globalThis, "document");
});

describe("알림 API 연결", () => {
  it("기기 등록은 token을 경로가 아니라 본문에 환경과 함께 PUT으로 보내고 204를 성공으로 읽는다", async () => {
    const fetchMock = respondWith(() => new Response(null, { status: 204 }));
    expect(await registerPushDevice(DEVICE_TOKEN, "SANDBOX")).toMatchObject({ ok: true, status: 204 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/api/me/push-devices");
    expect(init).toMatchObject({ method: "PUT", headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }) });
    expect(JSON.parse(String(init?.body))).toEqual({ token: DEVICE_TOKEN, environment: "SANDBOX" });
  });

  it("기기 등록 해제는 token을 본문에 담아 unregister로 POST를 보낸다", async () => {
    const fetchMock = respondWith(() => new Response(null, { status: 204 }));
    expect(await unregisterPushDevice(DEVICE_TOKEN)).toMatchObject({ ok: true, status: 204 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/api/me/push-devices/unregister");
    expect(init).toMatchObject({ method: "POST", headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }) });
    expect(JSON.parse(String(init?.body))).toEqual({ token: DEVICE_TOKEN });
  });

  it("설정 목록을 검증한 값으로 읽는다", async () => {
    const fetchMock = respondWith(() => new Response(JSON.stringify({ settings: [setting] })));
    expect(await fetchNotificationSettings()).toMatchObject({ ok: true, body: { settings: [setting] } });
    expect(fetchMock).toHaveBeenCalledWith("/api/me/notification-settings", expect.objectContaining({ method: "GET" }));
  });

  it("설정 저장은 대상과 시각, 요일만 PUT 본문으로 보내고 저장된 설정을 돌려준다", async () => {
    const fetchMock = respondWith(() => new Response(JSON.stringify(setting)));
    const savedFavorite = { ...target, id: "10000000-0000-4000-8000-000000000009" };
    const result = await saveNotificationSetting(savedFavorite, { time: "07:40", weekdays: setting.weekdays });
    expect(result).toMatchObject({ ok: true, body: setting });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/me/notification-settings",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ ...target, time: "07:40", weekdays: setting.weekdays }),
      }),
    );
  });

  it("설정 삭제는 id를 경로에 넣어 DELETE를 보낸다", async () => {
    const fetchMock = respondWith(() => new Response(null, { status: 204 }));
    expect(await deleteNotificationSetting(setting.id)).toMatchObject({ ok: true });
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/me/notification-settings/${setting.id}`,
      expect.objectContaining({ method: "DELETE" }),
    );
  });

  it("요일 순서가 섞여 와도 월요일부터 순서로 읽는다", async () => {
    respondWith(() => new Response(JSON.stringify({ ...setting, weekdays: ["SUN", "MON"] })));
    expect(await saveNotificationSetting(target, setting)).toMatchObject({
      ok: true,
      body: { weekdays: ["MON", "SUN"] },
    });
  });

  it.each<[string, unknown]>([
    ["빈 요일", { ...setting, weekdays: [] }],
    ["모르는 요일", { ...setting, weekdays: ["MON", "HOLIDAY"] }],
    ["겹친 요일", { ...setting, weekdays: ["MON", "MON"] }],
    ["초가 붙은 시각", { ...setting, time: "07:40:00" }],
    ["범위를 넘는 시각", { ...setting, time: "24:00" }],
    ["UUID가 아닌 id", { ...setting, id: "1" }],
    ["모르는 방향", { ...setting, direction: "LEFT" }],
  ])("%s가 담긴 저장 응답은 잘못된 응답으로 분류한다", async (_, body) => {
    respondWith(() => new Response(JSON.stringify(body)));
    expect(await saveNotificationSetting(target, setting)).toMatchObject({
      ok: false,
      failure: { kind: "malformed", status: 200 },
    });
  });

  it("목록 안 설정 하나라도 깨지면 목록 전체를 잘못된 응답으로 분류한다", async () => {
    respondWith(() => new Response(JSON.stringify({ settings: [setting, { ...setting, time: "7:40" }] })));
    expect(await fetchNotificationSettings()).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });

  it.each([
    [400, "INVALID_PUSH_DEVICE", () => registerPushDevice(DEVICE_TOKEN, "PRODUCTION")],
    [400, "INVALID_NOTIFICATION_SCHEDULE", () => saveNotificationSetting(target, setting)],
    [422, "NOTIFICATION_TARGET_NOT_FAVORITE", () => saveNotificationSetting(target, setting)],
    [404, "NOTIFICATION_SETTING_NOT_FOUND", () => deleteNotificationSetting(setting.id)],
  ] as const)("%i %s 오류를 계약 오류로 읽는다", async (status, code, send) => {
    respondWith(() => new Response(JSON.stringify({ code, message: code, requestId: "request-1" }), { status }));
    expect(await send()).toMatchObject({
      ok: false,
      failure: { kind: "contract", status, error: { code, requestId: "request-1" } },
    });
  });
});
