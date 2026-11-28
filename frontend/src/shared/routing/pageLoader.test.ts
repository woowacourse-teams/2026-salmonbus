import { describe, expect, jest, test } from "@jest/globals";
import { createPageLoader } from "./pageLoader";

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  let reject: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("createPageLoader", () => {
  test("load를 부르기 전에는 idle이고 import를 시작하지 않는다", () => {
    const load = jest.fn(() => Promise.resolve("page"));
    const loader = createPageLoader(load);

    expect(loader.snapshot()).toEqual({ status: "idle" });
    expect(load).not.toHaveBeenCalled();
  });

  test("동시에 여러 번 불러도 import는 한 번이고 같은 promise를 돌려준다", () => {
    const pending = deferred<string>();
    const load = jest.fn(() => pending.promise);
    const loader = createPageLoader(load);

    const first = loader.load();
    const second = loader.load();

    expect(load).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);
    expect(loader.snapshot().status).toBe("pending");
  });

  test("성공하면 ready가 되고 값을 스냅숏에 담는다", async () => {
    const loader = createPageLoader(() => Promise.resolve("page"));

    await loader.load();

    expect(loader.snapshot()).toMatchObject({ status: "ready", value: "page" });
  });

  test("실패하면 reset 전까지 같은 실패 promise를 돌려주고 다시 import하지 않는다", async () => {
    const failure = new Error("chunk");
    const load = jest.fn(() => Promise.reject(failure));
    const loader = createPageLoader(load);

    await expect(loader.load()).rejects.toBe(failure);
    await expect(loader.load()).rejects.toBe(failure);

    expect(load).toHaveBeenCalledTimes(1);
    expect(loader.snapshot()).toMatchObject({ status: "failed", error: failure });
  });

  test("미리 받기만 한 load가 실패하면 다음 load가 다시 import한다", async () => {
    const first = deferred<string>();
    const load = jest
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce("page");
    const loader = createPageLoader(load);

    loader.preload();
    first.reject(new Error("chunk"));
    await first.promise.catch(() => undefined);

    expect(loader.snapshot()).toEqual({ status: "idle" });
    await expect(loader.load()).resolves.toBe("page");
    expect(load).toHaveBeenCalledTimes(2);
  });

  test("미리 받는 중에 화면이 기다리기 시작한 load가 실패하면 reset 전까지 실패가 남는다", async () => {
    const pending = deferred<string>();
    const failure = new Error("chunk");
    const load = jest.fn(() => pending.promise);
    const loader = createPageLoader(load);

    loader.preload();
    const awaited = loader.load();
    pending.reject(failure);

    await expect(awaited).rejects.toBe(failure);
    await expect(loader.load()).rejects.toBe(failure);
    expect(load).toHaveBeenCalledTimes(1);
    expect(loader.snapshot()).toMatchObject({ status: "failed", error: failure });
  });

  test("실패 뒤 reset하면 idle로 돌아가고 다음 load가 다시 import한다", async () => {
    const load = jest
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error("chunk"))
      .mockResolvedValueOnce("page");
    const loader = createPageLoader(load);

    await expect(loader.load()).rejects.toThrow("chunk");
    loader.reset();

    expect(loader.snapshot()).toEqual({ status: "idle" });
    await expect(loader.load()).resolves.toBe("page");
    expect(load).toHaveBeenCalledTimes(2);
  });

  test("ready나 pending에서 reset은 아무것도 바꾸지 않는다", async () => {
    const pending = deferred<string>();
    const loader = createPageLoader(() => pending.promise);

    const promise = loader.load();
    loader.reset();
    expect(loader.snapshot().status).toBe("pending");

    pending.resolve("page");
    await promise;
    loader.reset();
    expect(loader.snapshot()).toMatchObject({ status: "ready", value: "page" });
  });
});
