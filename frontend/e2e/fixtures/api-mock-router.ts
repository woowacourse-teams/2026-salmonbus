import type { Frame, Page, Request, Route } from "@playwright/test";

type ApiRouteHandler = (route: Route) => Promise<void>;

export interface ApiMockRouter {
  route(handler: ApiRouteHandler): Promise<void>;
  dispose(): Promise<void>;
}

export async function createApiMockRouter(page: Page): Promise<ApiMockRouter> {
  const requests = new Map<Request, { finished: Promise<void>; finish: () => void }>();
  const errors: unknown[] = [];
  const unansweredRequests: string[] = [];
  let mocksRemoved = false;
  let documentRequest: Request | null = null;

  // context route는 모든 page route가 fallback한 뒤에 받고, page.unrouteAll() 뒤에도 페이지가 닫힐 때까지 남는다.
  await page.context().route("**/api/**", async (route) => {
    if (!mocksRemoved) {
      const request = route.request();
      const url = new URL(request.url());
      unansweredRequests.push(`${request.method()} ${url.pathname}${url.search}`);
    }
    // 페이지가 닫히는 중이면 끊기가 실패할 수 있다. 주소는 이미 남겼다.
    await route.abort().catch(() => {});
  });

  const onRequest = (request: Request) => {
    const previous = request.redirectedFrom();
    // Firefox는 가로챈 302의 requestfinished를 생략할 수 있어 다음 요청에서도 종료한다.
    if (previous !== null) onRequestFinished(previous);
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documentRequest = request;
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
  const onRequestFailed = (request: Request) => {
    if (request === documentRequest) documentRequest = null;
    onRequestFinished(request);
  };
  // 새 문서가 들어서면 브라우저는 이전 문서의 요청을 버리고, Playwright는 그 요청의 종료를 알리지 않는다.
  // pushState도 framenavigated를 내므로 문서 요청이 앞선 이동만 새 문서로 본다.
  const onFrameNavigated = (frame: Frame) => {
    if (frame !== page.mainFrame() || documentRequest === null) return;
    documentRequest = null;
    for (const request of Array.from(requests.keys())) onRequestFinished(request);
  };

  page.on("request", onRequest);
  page.on("requestfinished", onRequestFinished);
  page.on("requestfailed", onRequestFailed);
  page.on("framenavigated", onFrameNavigated);

  return {
    async route(handler: ApiRouteHandler) {
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
      try {
        // fallback으로 다음 콜백에 넘어가는 동안에도 요청 완료까지 mock 등록을 유지한다.
        while (requests.size > 0) {
          await Promise.all(Array.from(requests.values(), (request) => request.finished));
        }
        mocksRemoved = true;
        // mock을 지우는 순간 진행 중이던 route는 Playwright가 끊는 route를 거치지 않고 네트워크로 넘길 수 있어 네트워크부터 끊는다.
        await page.context().setOffline(true);
        // 요청 종료 후에도 handler에 남은 작업이 있으면 Playwright가 완료를 기다린다.
        await page.unrouteAll({ behavior: "wait" });
        if (errors.length > 0) throw new AggregateError(errors, "API mock 응답에 실패했습니다");
        if (unansweredRequests.length > 0)
          throw new Error(`mock이 응답하지 않은 API 요청을 끊었습니다\n${unansweredRequests.join("\n")}`);
      } finally {
        page.off("request", onRequest);
        page.off("requestfinished", onRequestFinished);
        page.off("requestfailed", onRequestFailed);
        page.off("framenavigated", onFrameNavigated);
      }
    },
  };
}
