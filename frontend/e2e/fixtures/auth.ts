import { test as apiTest, expect } from "./api";
import type { Route } from "@playwright/test";
import contract from "../../../contract/auth-v1.openapi.json" with { type: "json" };

const memberExample =
  contract.paths["/api/auth/session"].get.responses["200"].content["application/json"].examples.authenticated.value
    .member;
const profileExample =
  contract.paths["/api/me"].patch.responses["200"].content["application/json"].examples.profile.value;

export function redirectAuth(route: Route, destination: string, browserName: string) {
  // WebKit의 route.fulfill은 302를 지원하지 않아 같은 목적지의 페이지 이동으로 대체한다.
  return browserName === "webkit"
    ? route.fulfill({
        contentType: "text/html",
        body: `<script>location.replace(${JSON.stringify(destination).replace(/</g, "\\u003c")})</script>`,
      })
    : route.fulfill({ status: 302, headers: { Location: destination } });
}

export const testMember = {
  ...memberExample,
  nickname: "살몽",
  provider: "KAKAO" as "KAKAO" | "APPLE",
};

interface AuthMock {
  member: typeof testMember | null;
  enabled: boolean;
  refreshable: boolean;
  refreshFails: boolean;
  nicknameError: string | null;
  mutationFails: boolean;
  loginResult: "success" | "cancelled" | "failed";
  requests: { path: string; method: string; csrf: string | undefined }[];
}

export const test = apiTest.extend<{ auth: AuthMock }>({
  auth: [
    async ({ page, api, browserName }, use) => {
      const auth: AuthMock = {
        member: null,
        enabled: true,
        refreshable: false,
        refreshFails: false,
        nicknameError: null,
        mutationFails: false,
        loginResult: "success",
        requests: [],
      };
      // HTTP 로컬 서버의 테스트에서는 CSRF 읽기만 모의한다. Secure 쿠키 속성은 실환경에서 별도로 검증한다.
      await page.addInitScript(() =>
        Object.defineProperty(document, "cookie", { configurable: true, get: () => "__Host-XSRF-TOKEN=e2e-csrf" }),
      );
      await api.route(async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname;
        if (!/^\/api\/(auth|me)(\/|$)/.test(path)) {
          await route.fallback();
          return;
        }
        auth.requests.push({ path, method: request.method(), csrf: request.headers()["x-xsrf-token"] });
        const error = (status: number, code: string) =>
          route.fulfill({
            status,
            json: { code, message: code, requestId: "auth-e2e" },
            headers: { "Cache-Control": "no-store" },
          });
        if (!auth.enabled) {
          await error(404, "ENDPOINT_NOT_FOUND");
          return;
        }
        if (path === "/api/auth/session") {
          await route.fulfill({
            json: auth.member
              ? { authenticated: true, refreshable: false, member: auth.member }
              : { authenticated: false, refreshable: auth.refreshable },
            headers: { "Cache-Control": "no-store" },
          });
        } else if (/^\/api\/auth\/(kakao|apple)\/start$/.test(path)) {
          const returnTo = url.searchParams.get("returnTo") ?? "/";
          if (auth.loginResult === "success")
            auth.member = { ...testMember, provider: path.includes("apple") ? "APPLE" : "KAKAO" };
          const destination = new URL(returnTo, url.origin);
          if (auth.loginResult !== "success") destination.searchParams.set("login", auth.loginResult);
          await redirectAuth(route, destination.toString(), browserName);
        } else if (request.headers()["x-xsrf-token"] !== "e2e-csrf" && request.method() !== "GET") {
          await error(400, "CSRF_REJECTED");
        } else if (path === "/api/auth/refresh") {
          if (auth.refreshFails) await error(401, "UNAUTHENTICATED");
          else {
            auth.member ??= { ...testMember };
            auth.refreshable = false;
            await route.fulfill({ status: 204 });
          }
        } else if (auth.mutationFails) {
          await error(503, "SERVICE_UNAVAILABLE");
        } else if (path === "/api/auth/logout") {
          auth.member = null;
          await route.fulfill({ status: 204 });
        } else if (path === "/api/me" && request.method() === "PATCH") {
          if (auth.nicknameError) await error(auth.nicknameError === "NICKNAME_TAKEN" ? 409 : 400, auth.nicknameError);
          else if (!auth.member) await error(401, "UNAUTHENTICATED");
          else {
            auth.member = { ...auth.member, nickname: request.postDataJSON().nickname };
            await route.fulfill({ json: { ...profileExample, ...auth.member } });
          }
        } else if (path === "/api/me" && request.method() === "DELETE") {
          if (auth.member?.provider === "APPLE") await error(409, "REAUTH_REQUIRED");
          else {
            auth.member = null;
            await route.fulfill({ status: 204 });
          }
        } else if (path === "/api/me/withdrawal/apple") {
          await route.fulfill({
            json: { startUrl: "/api/auth/apple/start?intent=withdraw&ticket=e2e-ticket&returnTo=%2F" },
          });
        } else {
          await error(404, "ENDPOINT_NOT_FOUND");
        }
      });
      await use(auth);
    },
    { auto: true },
  ],
});

export { expect };
