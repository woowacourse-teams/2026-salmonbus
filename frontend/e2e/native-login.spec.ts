import type { Page } from "@playwright/test";
import { test, expect, testMember } from "./fixtures/auth";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

type AppAnswer =
  | { status: "success"; idToken: string; authorizationCode?: string }
  | { status: "cancelled" }
  | { status: "failed"; reason: string };

/** 앱 WebView 흉내. auth.signIn을 받으면 answer를 같은 id로 돌려준다. */
async function openInApp(page: Page, answer: AppAnswer) {
  await page.addInitScript((answer) => {
    window.__SALMONBUS_APP__ = { appVersion: "1.0.0", capabilities: ["auth.signIn"] };
    window.ReactNativeWebView = {
      postMessage(raw: string) {
        const message = JSON.parse(raw) as { type: string; payload: { id: string; provider: string } };
        if (message.type !== "auth.signIn") return;
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
  }, answer);
}

test("앱 안에서 서버 네이티브 API가 있으면 카카오톡 로그인 결과로 로그인하고 복귀한다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "success", idToken: "kakao.id.token" });
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  expect(auth.requests.map((request) => request.path)).not.toContain("/api/auth/kakao/start");
});

test("앱 로그인을 취소하면 로그인 화면에 남아 다시 시도할 수 있다", async ({ page, auth }) => {
  auth.nativeApi = true;
  await openInApp(page, { status: "cancelled" });
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page.getByText("로그인을 취소했어요.")).toBeVisible();
  await expect(page).toHaveURL("/login?returnTo=%2Fmypage");
  await expect(page.getByRole("button", { name: "카카오로 계속하기" })).toBeEnabled();
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
  expect(auth.requests.map((request) => request.path)).not.toContain("/api/auth/apple/start");
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
});
