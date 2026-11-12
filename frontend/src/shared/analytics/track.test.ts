import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";

interface FakeSdkOptions {
  failToLoad?: boolean;
  failToInit?: boolean;
}

type Calls = string[];

const ONE_TICK = () => new Promise((resolve) => setTimeout(resolve, 0));

function installBrowserGlobals(storedOptOut: boolean) {
  const loadListeners: Array<() => void> = [];
  const storage = new Map<string, string>(storedOptOut ? [["salmonbus.analyticsOptOut", "true"]] : []);
  Object.assign(globalThis, {
    __AMPLITUDE_API_KEY__: "test-key",
    document: { readyState: "loading", querySelector: () => null },
    window: {
      addEventListener: (type: string, listener: () => void) => {
        if (type === "load") loadListeners.push(listener);
      },
      setTimeout: (callback: () => void, delay: number, ...rest: unknown[]) => setTimeout(callback, delay, ...rest),
      localStorage: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => void storage.set(key, value),
        removeItem: (key: string) => void storage.delete(key),
      },
    },
  });
  return { finishLoading: () => loadListeners.forEach((listener) => listener()) };
}

async function setUp({
  storedOptOut = false,
  failToLoad = false,
  failToInit = false,
}: { storedOptOut?: boolean } & FakeSdkOptions = {}) {
  jest.resetModules();
  const calls: Calls = [];
  const initOptions: Array<{ optOut: boolean | undefined }> = [];
  const loadedAt: number[] = [];
  jest.doMock("@amplitude/unified", () => {
    if (failToLoad) throw new Error("chunk를 받지 못했습니다");
    loadedAt.push(Date.now());
    return {
      add: () => calls.push("add"),
      initAll: (_key: string, options: { analytics: { optOut?: boolean } }) => {
        if (failToInit) throw new Error("초기화에 실패했습니다");
        calls.push("initAll");
        // 실제 initAll은 autocapture로 세션 시작과 Page Viewed를 만든다.
        calls.push("autocapture session_start", "autocapture Page Viewed");
        initOptions.push({ optOut: options.analytics.optOut });
      },
      setUserId: (userId: string | undefined) => calls.push(`setUserId ${userId}`),
      setOptOut: (optedOut: boolean) => calls.push(`setOptOut ${optedOut}`),
      setTransport: (transport: string) => calls.push(`setTransport ${transport}`),
      flush: () => calls.push("flush"),
      track: (event: string, _properties: unknown, ...extraArguments: unknown[]) => {
        calls.push(extraArguments.length === 0 ? `track ${event}` : `track ${event} with-extra-arguments`);
        return { promise: Promise.resolve() };
      },
    };
  });
  const browser = installBrowserGlobals(storedOptOut);
  const analytics = await import("./analytics");
  const tracking = await import("./track");
  analytics.initAnalytics();
  return { ...browser, ...analytics, ...tracking, calls, initOptions, loadedAt };
}

async function finishLoadingAndSettle(setup: {
  finishLoading: () => void;
  whenAnalyticsSdkReady: () => Promise<unknown> | null;
}) {
  setup.finishLoading();
  await setup.whenAnalyticsSdkReady();
  await ONE_TICK();
}

describe("SDK가 오기 전에 부른 호출", () => {
  it("SDK가 오기 전에 부른 이벤트는 세션 시작과 Page Viewed 뒤에 부른 순서대로 나가고, 시각을 따로 넘기지 않는다", async () => {
    const setup = await setUp();
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    setup.track("login_started", { provider: "KAKAO", entry: "home" });
    expect(setup.calls).toEqual([]);

    await finishLoadingAndSettle(setup);

    expect(setup.calls).toEqual([
      "add",
      "initAll",
      "autocapture session_start",
      "autocapture Page Viewed",
      "track login_finished",
      "track login_started",
    ]);
  });

  it("회원 ID를 먼저 적용한 뒤 이벤트를 보낸다", async () => {
    const setup = await setUp();
    setup.trackAsMember("member-1", "login_finished", { provider: "KAKAO", result: "succeeded" });
    await finishLoadingAndSettle(setup);

    const identifyIndex = setup.calls.indexOf("setUserId member-1");
    const trackIndex = setup.calls.findIndex((call) => call.startsWith("track login_finished"));
    expect(identifyIndex).toBeGreaterThan(-1);
    expect(identifyIndex).toBeLessThan(trackIndex);
  });

  it("로그아웃하면 회원 ID를 비운다", async () => {
    const setup = await setUp();
    await finishLoadingAndSettle(setup);
    setup.setAnalyticsMemberId("member-1");
    setup.setAnalyticsMemberId(null);
    await ONE_TICK();

    expect(setup.calls.slice(-2)).toEqual(["setUserId member-1", "setUserId undefined"]);
  });
});

describe.each([
  ["받지 못했을 때", { failToLoad: true }],
  ["받았지만 시작하지 못했을 때", { failToInit: true }],
])("SDK를 %s", (_situation, failure) => {
  it("이벤트, 회원 ID, 수집 거부 전환, 떠나기 전 이벤트가 예외 없이 지나가고 처리되지 않은 거부도 없다", async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown) => unhandled.push(reason);
    process.on("unhandledRejection", onUnhandled);
    jest.spyOn(console, "warn").mockImplementation(() => {});

    const setup = await setUp(failure);
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    setup.trackAsMember("member-1", "login_finished", { provider: "KAKAO", result: "succeeded" });
    await finishLoadingAndSettle(setup);

    expect(() => setup.setAnalyticsMemberId("member-2")).not.toThrow();
    expect(() => setup.setAnalyticsOptOut(true)).not.toThrow();
    expect(() => setup.setAnalyticsOptOut(false)).not.toThrow();
    await expect(
      setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" }),
    ).resolves.toBeUndefined();
    await ONE_TICK();
    await ONE_TICK();

    expect(setup.isAnalyticsEnabled()).toBe(false);
    expect(setup.calls.filter((call) => /^(track|setUserId|setOptOut|setTransport|flush)/.test(call))).toEqual([]);
    expect(unhandled).toEqual([]);
    process.off("unhandledRejection", onUnhandled);
  });
});

describe("수집 거부", () => {
  it("거부 상태로 시작하면 SDK를 받지 않고 이벤트도 보내지 않는다", async () => {
    const setup = await setUp({ storedOptOut: true });
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    await finishLoadingAndSettle(setup);

    expect(setup.loadedAt).toEqual([]);
    expect(setup.calls).toEqual([]);
  });

  it("거부 상태로 시작했다가 풀면 그때 SDK를 받아 시작한다", async () => {
    const setup = await setUp({ storedOptOut: true });
    setup.setAnalyticsOptOut(false);
    await finishLoadingAndSettle(setup);
    setup.track("login_started", { provider: "KAKAO", entry: "home" });
    await ONE_TICK();

    expect(setup.loadedAt).toHaveLength(1);
    expect(setup.initOptions).toEqual([{ optOut: false }]);
    expect(setup.calls.some((call) => call.startsWith("track login_started"))).toBe(true);
  });

  it("SDK를 받는 중에 거부를 켜면 그 전에 쌓인 이벤트를 보내지 않고, SDK는 꺼진 채로 시작한다", async () => {
    const setup = await setUp();
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    setup.setAnalyticsOptOut(true);
    await finishLoadingAndSettle(setup);

    expect(setup.calls.filter((call) => call.startsWith("track"))).toEqual([]);
    expect(setup.initOptions).toEqual([{ optOut: true }]);
    expect(setup.calls).toContain("setOptOut true");
  });

  it("거부를 켰다가 바로 풀어도 그 사이에 쌓인 이벤트는 보내지 않는다", async () => {
    const setup = await setUp();
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    setup.setAnalyticsOptOut(true);
    setup.setAnalyticsOptOut(false);
    setup.track("login_started", { provider: "KAKAO", entry: "home" });
    await finishLoadingAndSettle(setup);

    expect(setup.calls.filter((call) => call.startsWith("track"))).toHaveLength(1);
    expect(setup.calls.some((call) => call.startsWith("track login_started"))).toBe(true);
  });

  it("SDK를 받은 뒤 거부를 켜면 SDK에 알리고 이후 이벤트를 보내지 않는다", async () => {
    const setup = await setUp();
    await finishLoadingAndSettle(setup);
    setup.setAnalyticsOptOut(true);
    setup.track("login_started", { provider: "KAKAO", entry: "home" });
    await ONE_TICK();

    expect(setup.calls).toContain("setOptOut true");
    expect(setup.calls.some((call) => call.startsWith("track"))).toBe(false);
  });
});

describe("다른 사이트로 떠나기 전 이벤트", () => {
  beforeEach(() => {
    jest.useFakeTimers({ doNotFake: ["queueMicrotask", "nextTick"] });
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it("SDK가 300ms 안에 오지 않으면 보내지 않고 끝낸다", async () => {
    const setup = await setUp();
    const leaving = setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await jest.advanceTimersByTimeAsync(299);
    let settled = false;
    void leaving.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);

    await jest.advanceTimersByTimeAsync(1);
    await leaving;
    expect(setup.calls).toEqual([]);
  });

  it("SDK가 300ms 안에 오면 beacon으로 보내고 전송 방식을 되돌린다", async () => {
    const setup = await setUp();
    const leaving = setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await jest.advanceTimersByTimeAsync(100);
    setup.finishLoading();
    await jest.advanceTimersByTimeAsync(10);
    await leaving;

    expect(setup.calls.slice(-4)).toEqual([
      "setTransport beacon",
      "track login_started",
      "flush",
      "setTransport fetch",
    ]);
  });
});
