import { test, expect, type Page, type Request, type Route } from "@playwright/test";
import { createApiMockRouter } from "./fixtures/api-mock-router";
import { apiPaths } from "./fixtures/mock-data";

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test.beforeEach(async ({ page }) => {
  // 앱 요청 없이 mock의 등록과 해제 순서만 검사할 수 있는 같은 origin의 빈 페이지다.
  await page.route("**/", (route) => route.fulfill({ contentType: "text/html", body: "<!doctype html>" }));
  await page.goto("/");
});

async function captureForwardedRequests(page: Page) {
  const forwarded: string[] = [];
  await page.context().route("**/api/**", async (route) => {
    forwarded.push(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 502, json: { forwarded: true } });
  });
  return forwarded;
}

test("mock 해제 뒤의 API 요청은 실제 서버로 전달하지 않고 차단한다", async ({ page }) => {
  const forwarded = await captureForwardedRequests(page);
  const router = createApiMockRouter(page);
  await router.route((route) => route.fulfill({ json: { mocked: true } }));

  const mocked = await page.evaluate(async (path) => (await fetch(path)).json(), apiPaths.watch);
  expect(mocked).toEqual({ mocked: true });
  expect(forwarded).toEqual([]);

  await router.dispose();
  const blocked = await page.evaluate(async (path) => {
    try {
      await fetch(path);
      return false;
    } catch {
      return true;
    }
  }, apiPaths.watch);
  expect(blocked).toBe(true);
  expect(forwarded).toEqual([]);
});

test("실행 중 mock을 벗어난 API 요청은 차단하고 누출된 경로를 보고한다", async ({ page }) => {
  const forwarded = await captureForwardedRequests(page);
  const router = createApiMockRouter(page);
  await router.route((route) => route.fulfill({ json: { mocked: true } }));
  await page.unrouteAll({ behavior: "wait" });

  const blocked = await page.evaluate(async (path) => {
    try {
      await fetch(path);
      return false;
    } catch {
      return true;
    }
  }, apiPaths.watch);
  expect(blocked).toBe(true);
  expect(forwarded).toEqual([]);
  await expect(router.dispose()).rejects.toMatchObject({
    errors: [expect.objectContaining({ message: expect.stringContaining(apiPaths.watch) })],
  });
});

test("API에서 페이지로 리다이렉트한 요청도 mock 해제 전에 종료한다", async ({ page, browserName }) => {
  test.skip(browserName === "webkit", "WebKit의 route.fulfill은 302 응답을 지원하지 않는다");
  const router = createApiMockRouter(page);
  await router.route((route) =>
    new URL(route.request().url()).pathname === "/api/redirect-start"
      ? route.fulfill({ status: 302, headers: { Location: "/?redirect=finished" } })
      : route.fulfill({ status: 404, json: { code: "ENDPOINT_NOT_FOUND", message: "test", requestId: "test" } }),
  );
  await page.goto("/api/redirect-start");
  await expect(page).toHaveURL("/?redirect=finished");
  await router.dispose();
});

test("빠른 404 mock이 끝나도 느린 보드와 차량 mock이 완료된 뒤 해제한다", async ({ page }) => {
  const forwarded = await captureForwardedRequests(page);
  const router = createApiMockRouter(page);
  const endpoints = ["board", "vehicles", "missing"] as const;
  type Endpoint = (typeof endpoints)[number];
  const entered = { board: deferred(), vehicles: deferred(), missing: deferred() };
  const release = { board: deferred(), vehicles: deferred(), missing: deferred() };
  const missingFinished = deferred();
  let cleanup: Promise<void> | undefined;
  let disposed = false;

  try {
    await router.route(async (route) => {
      const endpoint = new URL(route.request().url()).pathname.split("/").at(-1) as Endpoint;
      entered[endpoint].resolve();
      await release[endpoint].promise;
      if (endpoint === "missing") {
        await route.fulfill({ status: 404, json: { endpoint } });
        missingFinished.resolve();
      } else {
        const now = await page.evaluate(() => Date.now());
        await route.fulfill({ headers: { Date: new Date(now).toUTCString() }, json: { endpoint } });
      }
    });
    const responses = page.evaluate(async (endpoints) => {
      return await Promise.all(endpoints.map(async (endpoint) => (await fetch(`/api/cleanup/${endpoint}`)).json()));
    }, endpoints);
    await Promise.all(endpoints.map((endpoint) => entered[endpoint].promise));

    cleanup = router.dispose().then(() => {
      disposed = true;
    });
    release.missing.resolve();
    await missingFinished.promise;
    await page.evaluate(() => Date.now());
    expect(disposed).toBe(false);
    expect(forwarded).toEqual([]);

    release.board.resolve();
    release.vehicles.resolve();
    await cleanup;
    expect(await responses).toEqual(endpoints.map((endpoint) => ({ endpoint })));
    expect(forwarded).toEqual([]);
  } finally {
    for (const endpoint of endpoints) release[endpoint].resolve();
    await (cleanup ?? router.dispose());
  }
});

for (const responseMode of ["fulfill", "abort"] as const) {
  test(`${responseMode}로 요청이 끝나도 handler가 완료된 뒤 해제한다`, async ({ page }) => {
    const router = createApiMockRouter(page);
    const handlerResponded = deferred();
    const releaseHandler = deferred();
    const isBoardRequest = (request: Request) => new URL(request.url()).pathname === "/api/cleanup/board";
    const requestFinished =
      responseMode === "fulfill"
        ? page.waitForEvent("requestfinished", isBoardRequest)
        : page.waitForEvent("requestfailed", isBoardRequest);
    let cleanup: Promise<void> | undefined;
    let disposed = false;
    let handlerFinished = false;

    try {
      await router.route(async (route) => {
        if (responseMode === "fulfill") {
          await route.fulfill({ json: { endpoint: "board" } });
        } else {
          await route.abort();
        }
        handlerResponded.resolve();
        await releaseHandler.promise;
        handlerFinished = true;
      });
      const response = page.evaluate(async () => {
        try {
          return await (await fetch("/api/cleanup/board")).json();
        } catch {
          return null;
        }
      });
      await Promise.all([requestFinished, handlerResponded.promise]);

      cleanup = router.dispose().then(() => {
        disposed = true;
      });
      await page.evaluate(() => Date.now());
      expect(disposed).toBe(false);

      releaseHandler.resolve();
      await cleanup;
      expect(handlerFinished).toBe(true);
      expect(await response).toEqual(responseMode === "fulfill" ? { endpoint: "board" } : null);
    } finally {
      releaseHandler.resolve();
      await (cleanup ?? router.dispose());
    }
  });
}

test("추가 mock의 fallback 뒤에 시작하는 기본 mock도 완료된 뒤 해제한다", async ({ page }) => {
  const forwarded = await captureForwardedRequests(page);
  const router = createApiMockRouter(page);
  const overrideEntered = deferred();
  const boardEntered = deferred();
  const missingEntered = deferred();
  const releaseFallback = deferred();
  const releaseBoard = deferred();
  const releaseMissing = deferred();
  const missingFinished = deferred();
  let cleanup: Promise<void> | undefined;
  let disposed = false;

  const fulfillBoard = async (route: Route) => {
    boardEntered.resolve();
    await releaseBoard.promise;
    await route.fulfill({ json: { endpoint: "board" } });
  };

  try {
    await router.route(fulfillBoard);
    await router.route(async (route) => {
      if (new URL(route.request().url()).pathname.endsWith("/missing")) {
        missingEntered.resolve();
        await releaseMissing.promise;
        await route.fulfill({ status: 404, json: { endpoint: "missing" } });
        missingFinished.resolve();
      } else {
        overrideEntered.resolve();
        await releaseFallback.promise;
        await route.fallback();
      }
    });
    const responses = page.evaluate(async () => {
      return await Promise.all(
        ["board", "missing"].map(async (endpoint) => (await fetch(`/api/cleanup/${endpoint}`)).json()),
      );
    });
    await Promise.all([overrideEntered.promise, missingEntered.promise]);

    cleanup = router.dispose().then(() => {
      disposed = true;
    });
    releaseMissing.resolve();
    await missingFinished.promise;
    await page.evaluate(() => Date.now());
    expect(disposed).toBe(false);

    releaseFallback.resolve();
    await boardEntered.promise;
    expect(disposed).toBe(false);
    releaseBoard.resolve();
    await cleanup;
    expect(await responses).toEqual([{ endpoint: "board" }, { endpoint: "missing" }]);
    expect(forwarded).toEqual([]);
  } finally {
    releaseMissing.resolve();
    releaseFallback.resolve();
    releaseBoard.resolve();
    await (cleanup ?? router.dispose());
  }
});
