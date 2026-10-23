import type { Page, Request, Route } from "@playwright/test";

type ApiRouteHandler = (route: Route) => Promise<void>;

export interface ApiMockRouter {
  route(handler: ApiRouteHandler): Promise<void>;
  dispose(): Promise<void>;
}

export function createApiMockRouter(page: Page): ApiMockRouter {
  const requests = new Map<Request, { finished: Promise<void>; finish: () => void }>();
  const errors: unknown[] = [];
  let disposing = false;
  // 페이지 mock을 해제한 뒤에도 context가 닫힐 때까지 실제 API 전송을 막는다.
  const guardReady = page.context().route("**/api/**", async (route) => {
    if (!disposing) {
      const request = route.request();
      errors.push(new Error(`API mock을 벗어난 요청: ${request.method()} ${request.url()}`));
    }
    await route.abort();
  });

  const onRequest = (request: Request) => {
    const previous = request.redirectedFrom();
    // Firefox는 가로챈 302의 requestfinished를 생략할 수 있어 다음 요청에서도 종료한다.
    if (previous !== null) onRequestFinished(previous);
    if (!new URL(request.url()).pathname.startsWith("/api/")) return;

    let finish!: () => void;
    const finished = new Promise<void>((resolve) => {
      finish = resolve;
    });
    requests.set(request, { finished, finish });
  };
  const onRequestFinished = (request: Request) => {
    requests.get(request)?.finish();
    requests.delete(request);
  };

  page.on("request", onRequest);
  page.on("requestfinished", onRequestFinished);
  page.on("requestfailed", onRequestFinished);

  return {
    async route(handler: ApiRouteHandler) {
      await guardReady;
      await page.route("**/api/**", async (route) => {
        try {
          await handler(route);
        } catch (error) {
          errors.push(error);
          // 응답하지 못한 요청도 종료하되, 원래 콜백 오류는 그대로 드러낸다.
          await route.abort().catch(() => {});
          throw error;
        }
      });
    },
    async dispose() {
      disposing = true;
      try {
        await guardReady;
        // fallback으로 다음 콜백에 넘어가는 동안에도 요청 완료까지 mock 등록을 유지한다.
        while (requests.size > 0) {
          await Promise.all(Array.from(requests.values(), (request) => request.finished));
        }
        // 요청 종료 후에도 handler에 남은 작업이 있으면 Playwright가 완료를 기다린다.
        await page.unrouteAll({ behavior: "wait" });
        if (errors.length > 0) throw new AggregateError(errors, "API mock 응답에 실패했습니다");
      } finally {
        page.off("request", onRequest);
        page.off("requestfinished", onRequestFinished);
        page.off("requestfailed", onRequestFinished);
      }
    },
  };
}
