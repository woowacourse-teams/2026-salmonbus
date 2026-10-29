import type { Page } from "@playwright/test";
import { test, expect, testMember, NATIVE_NONCE } from "./fixtures/auth";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

declare global {
  interface Window {
    signInRequests: { type: string; payload: { id: string; provider: string; nonce: string } }[];
  }
}

type AppAnswer =
  | { status: "success"; idToken: string; authorizationCode?: string }
  | { status: "cancelled" }
  | { status: "failed"; reason: string };

/** 앱 WebView 흉내. auth.signIn을 받으면 answer를 같은 id로 돌려준다. capabilities가 비면 옛 앱이다. */
async function openInApp(page: Page, answer: AppAnswer, capabilities: string[] = ["auth.signIn"]) {
  await page.addInitScript(
    ({ answer, capabilities }) => {
      window.signInRequests = [];
      window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities };
      window.ReactNativeWebView = {
        postMessage(raw: string) {
          const message = JSON.parse(raw) as Window["signInRequests"][number];
          if (message.type !== "auth.signIn") return;
          window.signInRequests.push(message);
          const { id, provider } = message.payload;
          queueMicrotask(() =>
            window.dispatchEvent(
              new CustomEvent("salmon:bridge", {
                detail: { v: 1, type: "auth.signInResult", payload: { id, provider, ...answer } },
              }),
            ),
          );
        },
      };
    },
    { answer, capabilities },
  );
}

const signInRequestsOf = (page: Page) => page.evaluate(() => window.signInRequests);

test("앱 안에서 서버 네이티브 API가 있으면 카카오톡 로그인 결과로 로그인하고 복귀한다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "success", idToken: "kakao.id.token" });
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  const paths = auth.requests.map((request) => request.path);
  expect(paths).toContain("/api/auth/kakao/native/nonce");
  expect(paths).toContain("/api/auth/kakao/native");
  expect(paths).not.toContain("/api/auth/kakao/start");
  expect(auth.requests.find((request) => request.path === "/api/auth/kakao/native")?.csrf).toBe("e2e-csrf");
});

test("앱이 보낸 요청에는 서버가 발급한 nonce가 들어간다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "cancelled" });
  await page.goto("/login");
  await page.getByRole("button", { name: "Apple로 계속하기" }).click();
  await expect(page.getByText("로그인을 취소했어요.")).toBeVisible();
  const [request] = await signInRequestsOf(page);
  expect(request?.payload).toMatchObject({ provider: "apple", nonce: NATIVE_NONCE });
});

test("앱 안이어도 서버에 네이티브 API가 없으면 WebView 로그인으로 간다", async ({ page, auth }) => {
  await openInApp(page, { status: "success", idToken: "unused" });
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  const paths = auth.requests.map((request) => request.path);
  expect(paths).toContain("/api/auth/kakao/native/nonce");
  expect(paths).toContain("/api/auth/kakao/start");
  expect(await signInRequestsOf(page)).toEqual([]);
});

test("auth.signIn을 모르는 앱은 서버에 묻지 않고 WebView 로그인으로 간다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "success", idToken: "unused" }, ["haptic.impact"]);
  await page.goto("/login");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL("/");
  const paths = auth.requests.map((request) => request.path);
  expect(paths).not.toContain("/api/auth/kakao/native/nonce");
  expect(paths).toContain("/api/auth/kakao/start");
});

test("앱이 그 제공자로 로그인할 수 없다고 하면 WebView 로그인으로 간다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "failed", reason: "unavailable" });
  await page.goto("/login");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL("/");
  expect(auth.requests.map((request) => request.path)).toContain("/api/auth/kakao/start");
});

test("앱 로그인을 취소하면 로그인 화면에 남아 다시 시도할 수 있다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "cancelled" });
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page.getByText("로그인을 취소했어요.")).toBeVisible();
  await expect(page).toHaveURL("/login?returnTo=%2Fmypage");
  await expect(page.getByRole("button", { name: "카카오로 계속하기" })).toBeEnabled();
  expect(auth.requests.map((request) => request.path)).not.toContain("/api/auth/kakao/native");
});

test("앱 로그인이 실패하면 실패 안내를 띄우고 다시 시도할 수 있다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "failed", reason: "error" });
  await page.goto("/login");
  await page.getByRole("button", { name: "Apple로 계속하기" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "다시 로그인", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("button", { name: "Apple로 계속하기" })).toBeEnabled();
});

test("앱 안 Apple 회원은 바텀시트로 다시 인증해 탈퇴한다", async ({ page, auth }) => {
  auth.nativeApi = true;
  auth.member = { ...testMember, provider: "APPLE" };
  await openInApp(page, { status: "success", idToken: "apple.id.token", authorizationCode: "apple-code" });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "회원탈퇴" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "회원탈퇴" }).click();
  await expect(page).toHaveURL("/");
  await expect(page.getByText("회원탈퇴가 완료됐어요")).toBeVisible();
  const paths = auth.requests.map((request) => request.path);
  expect(paths).toContain("/api/me/withdrawal/apple/native");
  expect(paths).not.toContain("/api/auth/apple/start");
});

test("앱 안 Apple 탈퇴의 다시 인증을 취소하면 회원을 유지한다", async ({ page, auth }) => {
  auth.nativeApi = true;
  auth.member = { ...testMember, provider: "APPLE" };
  await openInApp(page, { status: "cancelled" });
  await page.goto("/mypage");
  await page.getByRole("button", { name: "회원탈퇴" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "회원탈퇴" }).click();
  await expect(page.getByText("회원탈퇴를 취소했어요")).toBeVisible();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  expect(auth.requests.map((request) => request.path)).not.toContain("/api/me/withdrawal/apple/native");
});
