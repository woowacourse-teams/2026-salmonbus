import type { Page } from "@playwright/test";
import { test, expect, chatRouteId, chatAvailability } from "./fixtures/chat";
import { routeId } from "./fixtures/mock-data";
import type { NativeMapMessage, NativeMapEvent } from "../src/shared/native-map/protocol";

declare global {
  interface Window {
    nativeMapMessages: NativeMapMessage[];
  }
}

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "no-preference" });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.nativeMapMessages = [];
    window.SalmonNativeMap = {
      version: 1,
      platform: "ios",
      postMessage(raw) {
        const message = JSON.parse(raw) as NativeMapMessage;
        window.nativeMapMessages.push(message);
        if (message.type === "mount" || message.type === "layout") {
          queueMicrotask(() =>
            window.dispatchEvent(
              new CustomEvent("salmon:native-map", {
                detail: {
                  v: 1,
                  sessionId: message.sessionId,
                  revision: message.revision,
                  type: message.type === "mount" ? "ready" : "ack",
                  payload: message.type === "mount" ? {} : { revision: message.revision },
                },
              }),
            ),
          );
        }
      },
    };
  });
});

async function messages(page: Page) {
  return page.evaluate(() => window.nativeMapMessages);
}
async function open(page: Page, id = routeId) {
  await page.goto(`/routes/${id}`);
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
  await page.clock.runFor(64);
}
async function emit(page: Page, event: Pick<NativeMapEvent, "type" | "payload">, sessionId?: string) {
  await page.evaluate(
    ({ event, sessionId }) => {
      const mount = window.nativeMapMessages.find((message) => message.type === "mount")!;
      window.dispatchEvent(
        new CustomEvent("salmon:native-map", {
          detail: {
            v: 1,
            sessionId: sessionId ?? mount.sessionId,
            revision: mount.revision,
            ...event,
          },
        }),
      );
    },
    { event, sessionId },
  );
}

test("native host는 JS SDK 없이 scene과 관측별 motion을 보내고 브라우저 UI는 유지한다", async ({ page }) => {
  const sdkRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("oapi.map.naver.com")) sdkRequests.push(request.url());
  });
  await open(page);
  const initial = await messages(page);
  const mount = initial.find((message) => message.type === "mount");
  expect(mount?.payload.stops.length).toBeGreaterThan(0);
  expect(mount?.payload.paths.length).toBe(2);
  expect(initial.filter((message) => message.type === "motion").length).toBeGreaterThan(0);
  const layouts = initial.filter((message) => message.type === "layout");
  expect(layouts[0]?.payload.captureAll).toBe(true);
  expect(layouts.at(-1)?.payload.captureAll).toBe(false);
  await page.clock.runFor(1_000);
  const afterFrames = await messages(page);
  expect(afterFrames.filter((message) => message.type === "motion")).toEqual(
    initial.filter((message) => message.type === "motion"),
  );
  expect(sdkRequests).toEqual([]);
  await expect(page.getByRole("region", { name: "판정보드 정류장 목록", exact: true })).toBeVisible();
  expect(
    await page
      .getByRole("region", { name: "노선 지도", exact: true })
      .evaluate((element) => getComputedStyle(element).backgroundColor),
  ).toBe("rgba(0, 0, 0, 0)");
});

test("실제 transition 경계와 웹 버튼 영역을 전달하고 native 위치·선택 이벤트를 연결한다", async ({ page }) => {
  await open(page);
  await page.getByRole("slider", { name: "판정보드 높이" }).click();
  await page.clock.runFor(400);
  const layout = (await messages(page)).filter((message) => message.type === "layout").at(-1)!;
  const map = await page.getByRole("region", { name: "노선 지도", exact: true }).boundingBox();
  expect(layout.payload.mapRect.height).toBeCloseTo(map!.height, 0);
  expect(layout.payload.webRects.length).toBeGreaterThanOrEqual(3);
  expect(layout.payload.transitioning).toBe(false);
  await page.getByRole("button", { name: "현재 위치 보기", exact: true }).click();
  const location = (await messages(page)).find((message) => message.type === "location")!;
  await emit(page, {
    type: "locationResult",
    payload: { requestId: location.payload.requestId, coordinate: { latitude: 37.4, longitude: 127.1 } },
  });
  expect((await messages(page)).filter((message) => message.type === "camera").at(-1)?.payload.command).toBe(
    "location",
  );
  await emit(page, { type: "stopPress", payload: { selection: { direction: "UP", sequence: 1 } } }, "stale-session");
  await emit(page, { type: "stopPress", payload: { selection: { direction: "UP", sequence: 1 } } });
  await page.clock.runFor(64);
  expect(
    (await messages(page)).filter((message) => message.type === "updateScene").at(-1)?.payload.selectedStop,
  ).toEqual({ direction: "UP", sequence: 1 });
  await emit(page, { type: "mapPress", payload: {} });
  await page.clock.runFor(64);
  expect(
    (await messages(page)).filter((message) => message.type === "updateScene").at(-1)?.payload.selectedStop,
  ).toBeNull();
});

test("body portal 채팅을 hit region에 포함하고 전체 modal은 native 지도 입력도 차단한다", async ({ page, chat }) => {
  chat.enabled = true;
  await open(page, chatRouteId);
  await expect
    .poll(async () => {
      await page.clock.runFor(100);
      return page.getByRole("button", { name: `${chatAvailability.displayName} 채팅 열기`, exact: true }).count();
    })
    .toBe(1);
  await page.getByRole("button", { name: `${chatAvailability.displayName} 채팅 열기`, exact: true }).click();
  await page.clock.runFor(400);
  await page.getByRole("button", { name: "채팅 크게 보기", exact: true }).click();
  await page.clock.runFor(400);
  const layout = (await messages(page)).filter((message) => message.type === "layout").at(-1)!;
  expect(layout.payload.captureAll).toBe(true);
  expect(layout.payload.webRects.some((rect) => rect.height > 800)).toBe(true);
});

test("native 지도 오류 후에도 웹 목록을 유지하고 앱 전용 투명 모드를 정리한다", async ({ page }) => {
  await open(page);
  await emit(page, { type: "error", payload: { message: "SDK 인증 실패" } });
  await expect(page.getByText("지도를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "판정보드 정류장 목록", exact: true })).toBeVisible();
  expect(await page.locator("html").getAttribute("data-native-map")).toBeNull();
  expect((await messages(page)).at(-1)?.type).toBe("unmount");
});

test("겹친 transition과 두 손가락 중 하나가 끝나도 native gesture 잠금을 유지한다", async ({ page }) => {
  await open(page);
  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await sheet.evaluate((element) => {
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "height" }));
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "transform" }));
    element.dispatchEvent(new TransitionEvent("transitionend", { bubbles: true, propertyName: "height" }));
  });
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    true,
  );
  await sheet.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitioncancel", { bubbles: true, propertyName: "transform" })),
  );
  await page.clock.runFor(32);
  await page.evaluate(() => {
    const probe = document.createElement("div");
    probe.id = "native-drag-probe";
    probe.dataset.nativeMapDrag = "";
    document.body.append(probe);
    for (const pointerId of [11, 12])
      probe.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, pointerId }));
    probe.dispatchEvent(new PointerEvent("pointerup", { bubbles: true, pointerId: 12 }));
  });
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    true,
  );
  await page.evaluate(() => {
    const probe = document.getElementById("native-drag-probe")!;
    probe.dispatchEvent(new PointerEvent("lostpointercapture", { bubbles: true, pointerId: 11 }));
    probe.remove();
  });
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    false,
  );
});
