import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";

interface FakeSdkOptions {
  failToLoad?: boolean;
  failToInit?: boolean;
  holdInit?: boolean;
  holdSends?: boolean;
  throwOnTrack?: boolean;
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
  holdInit = false,
  holdSends = false,
  throwOnTrack = false,
}: { storedOptOut?: boolean } & FakeSdkOptions = {}) {
  jest.resetModules();
  const calls: Calls = [];
  const initOptions: Array<{ optOut: boolean | undefined }> = [];
  const loadedAt: number[] = [];
  const heldSends: Array<{ finish: () => void; fail: () => void }> = [];
  let rejectInit = () => {};
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
        if (!holdInit) return Promise.resolve();
        return new Promise<void>((_resolve, reject) => {
          rejectInit = () => reject(new Error("플러그인 시작에 실패했습니다"));
        });
      },
      setUserId: (userId: string | undefined) => calls.push(`setUserId ${userId}`),
      setOptOut: (optedOut: boolean) => calls.push(`setOptOut ${optedOut}`),
      setTransport: (transport: string) => calls.push(`setTransport ${transport}`),
      flush: () => calls.push("flush"),
      track: (event: string, _properties: unknown, ...extraArguments: unknown[]) => {
        calls.push(extraArguments.length === 0 ? `track ${event}` : `track ${event} with-extra-arguments`);
        if (throwOnTrack) throw new Error("track이 던졌습니다");
        if (!holdSends) return { promise: Promise.resolve() };
        const promise = new Promise<void>((resolve, reject) => {
          heldSends.push({ finish: resolve, fail: () => reject(new Error("전송에 실패했습니다")) });
        });
        return { promise };
      },
    };
  });
  const browser = installBrowserGlobals(storedOptOut);
  const analytics = await import("./analytics");
  const tracking = await import("./track");
  analytics.initAnalytics();
  Object.assign((globalThis as { window: object }).window, {
    engagement: {
      disable: () => calls.push("engagement disable"),
      enable: () => calls.push("engagement enable"),
    },
  });
  return {
    ...browser,
    ...analytics,
    ...tracking,
    calls,
    initOptions,
    loadedAt,
    heldSends,
    rejectInit: () => rejectInit(),
  };
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
  });
});

describe("SDK를 넘겨받은 뒤 시작이 거부될 때", () => {
  async function setUpStartedSdk() {
    const warn = jest.spyOn(console, "warn").mockImplementation(() => {});
    const setup = await setUp({ holdInit: true });
    setup.track("login_finished", { provider: "KAKAO", result: "succeeded" });
    await finishLoadingAndSettle(setup);
    return { setup, warn };
  }

  it("그 전에 부른 이벤트는 SDK에 닿은 채로 두고, 경고를 한 번 남기고 분석을 끈다", async () => {
    const { setup, warn } = await setUpStartedSdk();
    expect(setup.isAnalyticsEnabled()).toBe(true);

    setup.rejectInit();
    await ONE_TICK();

    expect(setup.calls).toContain("track login_finished");
    expect(warn).toHaveBeenCalledTimes(1);
    expect(setup.isAnalyticsEnabled()).toBe(false);
    await expect(setup.whenAnalyticsSdkReady()).resolves.toBeNull();
  });

  it("거부된 뒤에 부른 이벤트와 회원 ID, 떠나기 전 이벤트는 SDK를 부르지 않는다", async () => {
    const { setup } = await setUpStartedSdk();
    setup.rejectInit();
    await ONE_TICK();
    const callsBefore = [...setup.calls];

    setup.track("login_started", { provider: "KAKAO", entry: "home" });
    setup.setAnalyticsMemberId("member-1");
    await setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await ONE_TICK();

    expect(setup.calls).toEqual(callsBefore);
  });

  it("거부된 뒤에도 수집 거부를 켜면 SDK에 전한다", async () => {
    const { setup } = await setUpStartedSdk();
    setup.rejectInit();
    await ONE_TICK();

    setup.setAnalyticsOptOut(true);
    await ONE_TICK();

    expect(setup.calls.slice(-2)).toEqual(["setOptOut true", "engagement disable"]);
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

  it("SDK를 기다리는 동안 거부를 켰다가 풀면 그 전에 부른 이벤트는 보내지 않는다", async () => {
    const setup = await setUp();
    const leaving = setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await jest.advanceTimersByTimeAsync(50);
    setup.setAnalyticsOptOut(true);
    setup.setAnalyticsOptOut(false);
    setup.finishLoading();
    await jest.advanceTimersByTimeAsync(10);
    await leaving;

    expect(setup.calls.filter((call) => /^(track|setTransport|flush)/.test(call))).toEqual([]);
  });

  it("보내기가 300ms보다 오래 걸리면 돌아간 뒤에도 보내기가 끝날 때까지 beacon으로 두고, 끝나면 fetch로 되돌린다", async () => {
    const setup = await setUp({ holdSends: true });
    setup.finishLoading();
    await jest.advanceTimersByTimeAsync(10);
    const leaving = setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await jest.advanceTimersByTimeAsync(300);
    await leaving;

    expect(setup.calls).toContain("setTransport beacon");
    expect(setup.calls).not.toContain("setTransport fetch");

    setup.heldSends[0]!.finish();
    await jest.advanceTimersByTimeAsync(0);
    expect(setup.calls.at(-1)).toBe("setTransport fetch");
  });

  it("보내기가 거부돼도 fetch로 되돌리고 처리되지 않은 거부를 만들지 않는다", async () => {
    const setup = await setUp({ holdSends: true });
    setup.finishLoading();
    await jest.advanceTimersByTimeAsync(10);
    const leaving = setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" });
    await jest.advanceTimersByTimeAsync(300);
    await leaving;
    setup.heldSends[0]!.fail();
    await jest.advanceTimersByTimeAsync(0);
    await jest.advanceTimersByTimeAsync(10);

    expect(setup.calls.at(-1)).toBe("setTransport fetch");
  });

  it("track이 그 자리에서 던지면 바로 fetch로 되돌린다", async () => {
    const setup = await setUp({ throwOnTrack: true });
    setup.finishLoading();
    await jest.advanceTimersByTimeAsync(10);
    await expect(
      setup.trackBeforeLeaving("login_started", { provider: "KAKAO", entry: "home" }),
    ).resolves.toBeUndefined();

    expect(setup.calls.slice(-3)).toEqual(["setTransport beacon", "track login_started", "setTransport fetch"]);
    expect(setup.calls).not.toContain("flush");
  });
});
