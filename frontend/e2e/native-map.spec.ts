import { mergeTests, type Page } from "@playwright/test";
import { test as chatTest, expect, chatRouteId, chatAvailability } from "./fixtures/chat";
import { test as mapTest } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";
import type { NativeMapMessage, NativeMapEvent } from "../src/shared/native-map/protocol";

const test = mergeTests(chatTest, mapTest).extend<{ nativeReady: boolean }>({
  nativeReady: [true, { option: true }],
});

declare global {
  interface Window {
    nativeMapMessages: NativeMapMessage[];
  }
}

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "no-preference" });

test.beforeEach(async ({ page, nativeReady }) => {
  await page.addInitScript((nativeReady) => {
    window.nativeMapMessages = [];
    window.SalmonNativeMap = {
      version: 1,
      platform: "ios",
      postMessage(raw) {
        const message = JSON.parse(raw) as NativeMapMessage;
        window.nativeMapMessages.push(message);
        if ((message.type === "mount" && nativeReady) || message.type === "layout") {
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
  }, nativeReady);
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
  expect(
    await page.getByTestId("route-map").evaluate((element) => getComputedStyle(element.parentElement!).backgroundColor),
  ).toBe("rgba(0, 0, 0, 0)");
});

test("randomUUID가 없는 환경에서도 native 지도 연결과 선택 이벤트가 동작한다", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(crypto, "randomUUID", { configurable: true, value: undefined });
  });
  await open(page);
  const mount = (await messages(page)).find((message) => message.type === "mount")!;
  expect(mount.sessionId).toMatch(/^[0-9a-f]{32}$/);
  await emit(page, { type: "stopPress", payload: { selection: { direction: "UP", sequence: 1 } } });
  await page.clock.runFor(64);
  expect(
    (await messages(page)).filter((message) => message.type === "updateScene").at(-1)?.payload.selectedStop,
  ).toEqual({ direction: "UP", sequence: 1 });
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

test("native 위치 권한 대기가 12초를 넘어도 결과를 받아 위치를 표시한다", async ({ page }) => {
  await open(page);
  const button = page.getByRole("button", { name: "현재 위치 보기", exact: true });
  await button.click();
  const location = (await messages(page)).find((message) => message.type === "location")!;
  await page.clock.runFor(15_000);
  await expect(button).toBeDisabled();
  await expect(page.getByText("위치를 확인하는 데 시간이 걸려요. 다시 눌러주세요")).toHaveCount(0);
  await emit(page, {
    type: "locationResult",
    payload: { requestId: location.payload.requestId, coordinate: { latitude: 37.4, longitude: 127.1 } },
  });
  await expect(button).toBeEnabled();
  expect((await messages(page)).filter((message) => message.type === "camera").at(-1)?.payload.command).toBe(
    "location",
  );
});

test("native 위치 조회 시간 초과와 권한 거부를 안내하고 재시도하며 지도 종료 시 요청을 정리한다", async ({ page }) => {
  await open(page);
  const button = page.getByRole("button", { name: "현재 위치 보기", exact: true });
  for (const error of ["timeout", "denied"] as const) {
    await button.click();
    const location = (await messages(page)).filter((message) => message.type === "location").at(-1)!;
    await emit(page, { type: "locationResult", payload: { requestId: location.payload.requestId, error } });
    await expect(button).toBeEnabled();
    await expect(
      page.getByText(
        error === "timeout"
          ? "위치를 확인하는 데 시간이 걸려요. 다시 눌러주세요"
          : "iPhone 설정에서 연어버스의 위치 권한을 허용해주세요",
        { exact: true },
      ),
    ).toBeVisible();
  }
  const errors: Error[] = [];
  page.on("pageerror", (error) => errors.push(error));
  await button.click();
  const location = (await messages(page)).filter((message) => message.type === "location").at(-1)!;
  await emit(page, { type: "error", payload: { message: "SDK 인증 실패" } });
  await emit(page, {
    type: "locationResult",
    payload: { requestId: location.payload.requestId, coordinate: { latitude: 37.4, longitude: 127.1 } },
  });
  await expect(button).toHaveCount(0);
  expect((await messages(page)).at(-1)?.type).toBe("unmount");
  expect(errors).toEqual([]);
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

test.describe("native 지도 준비 실패 시 JS 지도 전환", () => {
  test.use({ nativeReady: false });

  for (const failure of ["timeout", "native-timeout", "auth"] as const) {
    test(`${failure} 후 JS 지도를 유지하고 선택과 차량 이동을 연결한다`, async ({ page, naverMap, api }) => {
      api.cacheMaxAgeSeconds = 60;
      api.data.vehicles.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1")!.phase = "IN_TRANSIT";
      await page.goto(`/routes/${routeId}`);
      await expect.poll(async () => (await messages(page)).some((message) => message.type === "mount")).toBe(true);
      await emit(page, { type: "stopPress", payload: { selection: { direction: "UP", sequence: 1 } } });
      if (failure === "timeout") await page.clock.runFor(15_000);
      else
        await emit(page, {
          type: "error",
          payload: { message: failure === "auth" ? "지도 설정을 확인하지 못했어요" : "지도를 불러오지 못했어요" },
        });

      const map = page.getByRole("region", { name: "노선 지도", exact: true });
      await expect(map.getByTestId("naver-map-test-double")).toHaveCount(1);
      await page.clock.runFor(64);
      await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
      expect(await page.locator("html").getAttribute("data-native-map")).toBeNull();
      expect((await messages(page)).filter((message) => message.type === "unmount")).toHaveLength(1);
      await expect(map.locator('button[data-map-stop-sequence="1"][aria-pressed="true"]')).toHaveCount(1);

      const camera = await naverMap.snapshot();
      const mountCount = (await messages(page)).filter((message) => message.type === "mount").length;
      await emit(page, { type: "ready", payload: {} });
      await emit(page, { type: "error", payload: { message: "지도 설정을 확인하지 못했어요" } });
      await page.clock.runFor(64);
      expect(await naverMap.snapshot()).toEqual(camera);
      expect((await messages(page)).filter((message) => message.type === "mount")).toHaveLength(mountCount);
      await expect(map.getByTestId("naver-map-test-double")).toHaveCount(1);

      const bus = map.getByRole("button", { name: /부근 버스, 현재 잔여 좌석 5석/ });
      const before = await bus.evaluate((element) => element.parentElement!.style.cssText);
      await page.clock.runFor(1_000);
      expect(await bus.evaluate((element) => element.parentElement!.style.cssText)).not.toBe(before);
      await bus.evaluate((element: HTMLButtonElement) => element.click());
      await page.clock.runFor(600);
      expect((await naverMap.snapshot()).zoom).toBeGreaterThanOrEqual(16);
    });
  }

  test("JS 지도도 초기화에 실패하면 오류를 안내하고 목록을 유지한다", async ({ page }) => {
    await page.addInitScript(() => {
      delete window.naver;
    });
    await page.goto(`/routes/${routeId}`);
    await expect.poll(async () => (await messages(page)).some((message) => message.type === "mount")).toBe(true);
    await emit(page, { type: "error", payload: { message: "지도 설정을 확인하지 못했어요" } });
    await expect(page.getByText(/지도를 불러오지 못했어요|지도 설정을 준비 중이에요/)).toBeVisible();
    await expect(page.getByRole("region", { name: "판정보드 정류장 목록", exact: true })).toBeVisible();
    expect(await page.locator("html").getAttribute("data-native-map")).toBeNull();
    const count = (await messages(page)).filter((message) => message.type === "mount").length;
    await page.clock.runFor(16_000);
    expect((await messages(page)).filter((message) => message.type === "mount")).toHaveLength(count);
  });
});

test("transition 중 제거된 채팅 버튼은 정리하고 다른 UI가 움직이는 동안에는 잠금을 유지한다", async ({
  page,
  chat,
}) => {
  chat.enabled = true;
  await open(page, chatRouteId);
  const launcher = page.getByRole("button", { name: `${chatAvailability.displayName} 채팅 열기`, exact: true });
  await expect
    .poll(async () => {
      await page.clock.runFor(100);
      return launcher.count();
    })
    .toBe(1);
  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await sheet.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "transform" })),
  );
  await launcher.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "scale" })),
  );
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    true,
  );

  await launcher.click();
  await expect(launcher).toHaveCount(0);
  await page.clock.runFor(1_000);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    true,
  );

  await sheet.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitioncancel", { bubbles: true, propertyName: "transform" })),
  );
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    false,
  );
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
