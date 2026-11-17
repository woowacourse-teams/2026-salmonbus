import { describe, expect, it, jest } from "@jest/globals";
import { createHomeMapFeed, HOME_MAP_FEED_URL } from "./homeMapFeed";

const FEED = { stops: [{ id: "10001", name: "공원 앞", point: [127.1, 37.39], routes: [] }], routePaths: [] };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function setup(responses: (() => Promise<Response>)[] = []) {
  let now = 0;
  let nextId = 1;
  const timers = new Map<number, { at: number; run: () => void }>();
  const signals: AbortSignal[] = [];
  const fetch = jest.fn(async (_input: string, init: RequestInit) => {
    signals.push(init.signal!);
    const next = responses.shift();
    return next ? next() : Response.json(FEED);
  });
  const feed = createHomeMapFeed({
    fetch,
    now: () => now,
    setTimeout: (run, ms) => {
      const id = nextId++;
      timers.set(id, { at: now + ms, run });
      return id;
    },
    clearTimeout: (id) => {
      timers.delete(id);
    },
  });
  async function advance(ms: number) {
    const target = now + ms;
    for (;;) {
      const due = [...timers.entries()].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]);
      now = due[1].at;
      due[1].run();
      await flush();
    }
    now = target;
    await flush();
  }
  return { feed, fetch, signals, advance, setNow: (value: number) => (now = value) };
}

async function flush() {
  for (let i = 0; i < 10; i += 1) await Promise.resolve();
}

describe("homeMapFeed", () => {
  it("구독만 하면 요청하지 않는다", async () => {
    const { feed, fetch } = setup();
    feed.subscribe(() => {});
    await flush();
    expect(fetch).not.toHaveBeenCalled();
    expect(feed.getSnapshot().status).toBe("idle");
  });

  it("동시에 필요한 소비자가 여럿이어도 한 번만 요청하고 모두 알린다", async () => {
    const { feed, fetch } = setup();
    const first = jest.fn();
    const second = jest.fn();
    feed.subscribeNeeded(first);
    feed.subscribeNeeded(second);
    await flush();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]![0]).toBe(HOME_MAP_FEED_URL);
    expect(fetch.mock.calls[0]![1]).toMatchObject({ cache: "no-cache" });
    expect(first).toHaveBeenCalled();
    expect(second).toHaveBeenCalled();
    expect(feed.getSnapshot().status).toBe("ready");
    expect(feed.getSnapshot().data.stops.map((stop) => stop.id)).toEqual(["10001"]);
  });

  it("진행 중에 모두 떠나도 요청을 끊지 않고 다시 오면 합류한다", async () => {
    const pending = deferred<Response>();
    const { feed, fetch, signals } = setup([() => pending.promise]);
    const leave = feed.subscribeNeeded(() => {});
    await flush();
    leave();
    feed.subscribeNeeded(() => {});
    await flush();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(signals[0]!.aborted).toBe(false);
    pending.resolve(Response.json(FEED));
    await flush();
    expect(feed.getSnapshot().status).toBe("ready");
  });

  it("받은 뒤 300초 안에 다시 필요하면 요청하지 않고 지나면 이전 데이터를 유지한 채 다시 받는다", async () => {
    const changed = {
      ...FEED,
      routePaths: [
        [
          [127.1, 37.39],
          [127.2, 37.4],
        ],
      ],
    };
    const { feed, fetch, setNow } = setup([async () => Response.json(FEED), async () => Response.json(changed)]);
    const leave = feed.subscribeNeeded(() => {});
    await flush();
    leave();
    const loaded = feed.getSnapshot().data;
    setNow(299_999);
    feed.subscribeNeeded(() => {})();
    await flush();
    expect(fetch).toHaveBeenCalledTimes(1);
    setNow(300_000);
    feed.subscribeNeeded(() => {});
    expect(feed.getSnapshot().data).toBe(loaded);
    await flush();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(feed.getSnapshot().data.routePaths).toHaveLength(1);
  });

  it("같은 내용으로 다시 받으면 데이터 참조를 유지한다", async () => {
    const { feed, setNow } = setup();
    feed.subscribeNeeded(() => {})();
    await flush();
    const loaded = feed.getSnapshot();
    setNow(300_000);
    feed.subscribeNeeded(() => {});
    await flush();
    expect(feed.getSnapshot().data).toBe(loaded.data);
    expect(feed.getSnapshot()).toBe(loaded);
  });

  it.each([
    ["503", async () => new Response(null, { status: 503 })],
    ["네트워크 오류", async () => Promise.reject(new TypeError("Failed to fetch"))],
    ["JSON이 아닌 본문", async () => new Response("<html>")],
    ["레코드가 아닌 JSON", async () => Response.json(null)],
  ])("%s이면 실패로 두고 2초와 8초 뒤 다시 시도한 다음 멈춘다", async (_name, failure) => {
    const { feed, fetch, advance } = setup([failure, failure, failure]);
    feed.subscribeNeeded(() => {});
    await flush();
    expect(feed.getSnapshot().status).toBe("error");
    expect(feed.getSnapshot().data).toEqual({ stops: [], routePaths: [] });
    await advance(1_999);
    expect(fetch).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(fetch).toHaveBeenCalledTimes(2);
    await advance(8_000);
    expect(fetch).toHaveBeenCalledTimes(3);
    await advance(60_000);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(feed.getSnapshot().status).toBe("error");
  });

  it("실패 뒤 필요한 곳이 없으면 다시 시도하지 않고 다시 필요해지면 바로 받는다", async () => {
    const { feed, fetch, advance } = setup([async () => new Response(null, { status: 503 })]);
    const leave = feed.subscribeNeeded(() => {});
    await flush();
    leave();
    await advance(60_000);
    expect(fetch).toHaveBeenCalledTimes(1);
    feed.subscribeNeeded(() => {});
    await flush();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(feed.getSnapshot().status).toBe("ready");
  });

  it("10초 안에 응답이 없으면 끊고 실패로 둔다", async () => {
    const { feed, signals, advance } = setup([
      () =>
        new Promise<Response>((_resolve, reject) =>
          signals[0]!.addEventListener("abort", () => reject(new Error("aborted"))),
        ),
    ]);
    feed.subscribeNeeded(() => {});
    await flush();
    await advance(10_000);
    expect(signals[0]!.aborted).toBe(true);
    expect(feed.getSnapshot().status).toBe("error");
  });

  it("지도 데이터가 없는 레코드는 정상 빈 데이터로 두고 다시 시도하지 않는다", async () => {
    const { feed, fetch, advance } = setup([async () => Response.json({ events: [] })]);
    feed.subscribeNeeded(() => {});
    await flush();
    await advance(60_000);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(feed.getSnapshot()).toEqual({ status: "ready", data: { stops: [], routePaths: [] } });
  });

  it("다시 받다가 실패하면 이전 데이터를 유지한다", async () => {
    const { feed, setNow } = setup([async () => Response.json(FEED), async () => new Response(null, { status: 503 })]);
    feed.subscribeNeeded(() => {})();
    await flush();
    const loaded = feed.getSnapshot().data;
    setNow(300_000);
    feed.subscribeNeeded(() => {});
    await flush();
    expect(feed.getSnapshot().status).toBe("error");
    expect(feed.getSnapshot().data).toBe(loaded);
  });
});
