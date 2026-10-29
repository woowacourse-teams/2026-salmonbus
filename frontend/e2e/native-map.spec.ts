import { mergeTests, type Page } from "@playwright/test";
import { test as mapTest, expect } from "./fixtures/naver-map";
import { routeId } from "./fixtures/mock-data";
import type { BridgeEnvelope } from "../src/shared/bridge/bridge.types";
import { NATIVE_MAP_CAPABILITIES } from "../src/shared/native-map/bridge";
import type { NativeMapCommands, NativeMapEvent, NativeMapSession } from "../src/shared/native-map/protocol";
import { test as favoritesTest } from "./fixtures/favorites";
import { expectFocusRing, nextFocusKeyFor } from "./utils/focus-ring";

const test = mapTest.extend<{ nativeReady: boolean; appCapabilities: readonly string[] }>({
  nativeReady: [true, { option: true }],
  appCapabilities: [NATIVE_MAP_CAPABILITIES, { option: true }],
});
const favoriteNativeTest = mergeTests(test, favoritesTest);

/** 앱이 받은 map 메시지를 영역 이름을 떼고 세션 값을 꺼낸 모양. 아래 검사는 이 모양으로 읽는다. */
type MapCommandMessage = {
  [K in keyof NativeMapCommands]: NativeMapSession & { type: K; payload: NativeMapCommands[K] };
}[keyof NativeMapCommands];

declare global {
  interface Window {
    bridgeMessages: BridgeEnvelope[];
  }
}

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "no-preference" });

test.beforeEach(async ({ page, nativeReady, appCapabilities }) => {
  await page.addInitScript(
    ({ nativeReady, capabilities }) => {
      window.bridgeMessages = [];
      window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
      window.ReactNativeWebView = {
        postMessage(raw: string) {
          const message = JSON.parse(raw) as BridgeEnvelope & { payload: { sessionId: string; revision: number } };
          window.bridgeMessages.push(message);
          if ((message.type === "map.mount" && nativeReady) || message.type === "map.layout") {
            const { sessionId, revision } = message.payload;
            queueMicrotask(() =>
              window.dispatchEvent(
                new CustomEvent("salmon:bridge", {
                  detail: {
                    v: 1,
                    type: message.type === "map.mount" ? "map.ready" : "map.ack",
                    payload:
                      message.type === "map.mount"
                        ? { sessionId, revision }
                        : { sessionId, revision, appliedRevision: revision },
                  },
                }),
              ),
            );
          }
        },
      };
    },
    { nativeReady, capabilities: appCapabilities },
  );
});

async function messages(page: Page): Promise<MapCommandMessage[]> {
  const sent = await page.evaluate(() => window.bridgeMessages);
  return sent
    .filter((message) => message.type.startsWith("map."))
    .map((message) => {
      const { sessionId, revision, ...payload } = message.payload as NativeMapSession;
      return { type: message.type.slice("map.".length), sessionId, revision, payload } as MapCommandMessage;
    });
}
async function open(page: Page, id = routeId) {
  await page.goto(`/routes/${id}`);
  await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
  await page.clock.runFor(64);
}
async function emit(page: Page, event: NativeMapEvent, sessionId?: string) {
  const mount = (await messages(page)).find((message) => message.type === "mount")!;
  await page.evaluate(
    ({ type, payload }) => window.dispatchEvent(new CustomEvent("salmon:bridge", { detail: { v: 1, type, payload } })),
    {
      type: `map.${event.type}`,
      payload: { ...event.payload, sessionId: sessionId ?? mount.sessionId, revision: mount.revision },
    },
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
  await page.getByRole("slider", { name: "판정보드 높이" }).press("ArrowDown");
  await page.clock.runFor(400);
  // page.clock은 CSS transition을 끝내지 않으므로 실제 종료 후 layout을 기다린다.
  await expect
    .poll(async () => {
      await page.clock.runFor(32);
      return (await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning;
    })
    .toBe(false);
  const layout = (await messages(page)).filter((message) => message.type === "layout").at(-1)!;
  const map = await page.getByRole("region", { name: "노선 지도", exact: true }).boundingBox();
  expect(layout.payload.mapRect.height).toBeCloseTo(map!.height, 0);
  expect(layout.payload.webRects.length).toBeGreaterThanOrEqual(3);
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

test("native 지도에서 정류장이나 버스를 고르면 웹에서 키를 누른 뒤여도 정류장 줄에 테두리를 보이지 않는다", async ({
  page,
  browserName,
}) => {
  await open(page);
  const row = (sequence: number) =>
    page
      .getByRole("region", { name: "판정보드 정류장 목록", exact: true })
      .locator(`li[data-stop-sequence='${sequence}'] button[aria-expanded]`);
  await test.step("웹에서 키를 누른 뒤 앱 지도에서 정류장 누르기", async () => {
    await page.getByRole("button", { name: "현재 위치 보기", exact: true }).press("Shift");
    await emit(page, { type: "stopPress", payload: { selection: { direction: "UP", sequence: 3 } } });
    await page.clock.runFor(1_000);
    await expectFocusRing(row(3), "hidden");
  });
  const vehicle = (await messages(page))
    .filter((message) => message.type === "updateScene")
    .at(-1)!
    .payload.vehicles.find((item) => item.direction === "UP" && item.currentStopSequence !== 3)!;
  const selection = { direction: vehicle.direction, sequence: vehicle.currentStopSequence };
  await test.step("웹에서 키를 누른 뒤 앱 지도에서 다른 정류장의 버스 누르기", async () => {
    await page.keyboard.press("Shift");
    await emit(page, { type: "vehiclePress", payload: { key: vehicle.key, selection } });
    await page.clock.runFor(1_000);
    await expectFocusRing(row(selection.sequence), "hidden");
  });
  await test.step("키보드로 정류장 줄에 돌아오면 테두리", async () => {
    const tabKey = nextFocusKeyFor(browserName);
    await page.keyboard.press(`Shift+${tabKey}`);
    await page.keyboard.press(tabKey);
    await expectFocusRing(row(selection.sequence), "shown");
  });
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
    await expect(page.getByRole("button", { name: "설정 열기", exact: true })).toHaveCount(0);
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

test.describe("설정 화면을 열 수 있는 앱", () => {
  test.use({ appCapabilities: [...NATIVE_MAP_CAPABILITIES, "settings.open"] });

  test("위치 권한이 거부되면 안내를 남겨 두고 설정 열기로 앱에 설정 화면을 요청하거나 닫기로 닫는다", async ({
    page,
  }) => {
    await open(page);
    const locate = page.getByRole("button", { name: "현재 위치 보기", exact: true });
    const notice = page.getByText("iPhone 설정에서 연어버스의 위치 권한을 허용해주세요", { exact: true });
    const settingsRequests = async () =>
      (await page.evaluate(() => window.bridgeMessages)).filter((message) => message.type === "settings.open");
    const deny = async () => {
      await locate.click();
      const location = (await messages(page)).filter((message) => message.type === "location").at(-1)!;
      await emit(page, { type: "locationResult", payload: { requestId: location.payload.requestId, error: "denied" } });
      await expect(notice).toBeVisible();
    };

    await deny();
    await page.clock.runFor(10_000);
    await expect(notice).toBeVisible();
    await page.getByRole("button", { name: "설정 열기", exact: true }).click();
    expect(await settingsRequests()).toEqual([{ v: 1, type: "settings.open", payload: {} }]);
    await expect(notice).toHaveCount(0);

    await deny();
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(notice).toHaveCount(0);
    expect(await settingsRequests()).toHaveLength(1);
  });

  test("위치 권한 안내는 닫기를 왼쪽, 설정 열기를 오른쪽에 두고 Tab도 같은 순서로 옮겨 간다", async ({
    page,
    browserName,
  }) => {
    await open(page);
    await page.getByRole("button", { name: "현재 위치 보기", exact: true }).click();
    const location = (await messages(page)).filter((message) => message.type === "location").at(-1)!;
    await emit(page, { type: "locationResult", payload: { requestId: location.payload.requestId, error: "denied" } });
    const dismiss = page.getByRole("button", { name: "닫기", exact: true });
    const settings = page.getByRole("button", { name: "설정 열기", exact: true });
    await expect(settings).toBeVisible();
    expect((await dismiss.boundingBox())!.x).toBeLessThan((await settings.boundingBox())!.x);
    await dismiss.focus();
    // WebKit은 Safari처럼 Option+Tab일 때만 버튼으로 초점을 옮긴다.
    await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
    await expect(settings).toBeFocused();
  });
});

favoriteNativeTest("즐겨찾기 dialog를 열면 native 지도 입력을 차단하고 닫으면 복구한다", async ({ page, api }) => {
  await open(page);
  const stop = api.data.watch.stops.find((stop) => stop.direction === "UP" && stop.boardingAllowed)!;
  await page.getByRole("button", { name: `${stop.name} 즐겨찾기`, exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.captureAll).toBe(true);
  await page.getByRole("dialog").getByRole("button", { name: "취소", exact: true }).click();
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.captureAll).toBe(false);
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

  test("즐겨찾기 정류장을 초기 native scene에 담고 이전 앱용 카메라 명령도 유지한다", async ({ page, api }) => {
    const stop = api.data.watch.stops.find((stop) => stop.direction === "DOWN" && stop.boardingAllowed)!;
    await page.goto(`/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`);
    await expect.poll(async () => (await messages(page)).some((message) => message.type === "mount")).toBe(true);
    const mount = (await messages(page)).find((message) => message.type === "mount")!;
    expect(mount.payload.selectedStop).toEqual({ direction: "DOWN", sequence: stop.sequence });
    expect(mount.payload.activeDirection).toBe("DOWN");
    const stopCommands = async () =>
      (await messages(page)).filter((message) => message.type === "camera" && message.payload.command === "stop");
    expect(await stopCommands()).toHaveLength(0);
    await emit(page, { type: "ready", payload: {} });
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    expect(await stopCommands()).toEqual([
      expect.objectContaining({
        payload: { command: "stop", selection: { direction: "DOWN", sequence: stop.sequence } },
      }),
    ]);
    await page.clock.runFor(1_000);
    expect(await stopCommands()).toHaveLength(1);
  });

  test("즐겨찾기 정류장이 유효하지 않으면 초기 native scene에서 선택하지 않는다", async ({ page }) => {
    await page.goto(`/routes/${routeId}?direction=DOWN&stopId=unknown-stop`);
    await expect.poll(async () => (await messages(page)).some((message) => message.type === "mount")).toBe(true);
    const mount = (await messages(page)).find((message) => message.type === "mount")!;
    expect(mount.payload.selectedStop).toBeNull();
    await emit(page, { type: "ready", payload: {} });
    await expect(page.getByRole("button", { name: "현재 위치 보기", exact: true })).toBeEnabled();
    expect((await messages(page)).filter((message) => message.type === "camera")).toHaveLength(0);
  });

  for (const failure of ["timeout", "native-timeout", "auth"] as const) {
    test(`${failure} 후 JS 지도를 유지하고 선택과 차량 이동을 연결한다`, async ({ page, naverMap, api }) => {
      api.cacheMaxAgeSeconds = 60;
      api.data.watch.vehicles.find((vehicle) => vehicle.vehicleId === "bus-1")!.phase = "IN_TRANSIT";
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

      await page.clock.runFor(600);
      expect((await naverMap.snapshot()).zoom).toBe(15);

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

test("transition 중 제거된 요소는 정리하고 다른 UI가 움직이는 동안에는 잠금을 유지한다", async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    const probe = document.createElement("div");
    probe.id = "native-transition-probe";
    probe.dataset.nativeMapHit = "";
    document.body.append(probe);
  });
  const probe = page.locator("#native-transition-probe");
  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await sheet.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "transform" })),
  );
  await probe.evaluate((element) =>
    element.dispatchEvent(new TransitionEvent("transitionrun", { bubbles: true, propertyName: "scale" })),
  );
  await page.clock.runFor(32);
  expect((await messages(page)).filter((message) => message.type === "layout").at(-1)?.payload.transitioning).toBe(
    true,
  );

  await probe.evaluate((element) => element.remove());
  await expect(probe).toHaveCount(0);
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
