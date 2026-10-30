import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { BRIDGE_EVENT } from "../bridge/bridge";
import { signInInApp, withdrawAppleInApp } from "./nativeSignIn";

const NONCE = "n0nce-from-server_0123456789";
const originalFetch = global.fetch;

function response(status: number, body?: unknown) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status });
}

function notFound() {
  return response(404, { code: "ENDPOINT_NOT_FOUND", message: "요청한 경로를 찾을 수 없습니다.", requestId: "r" });
}

/** 앱 WebView 흉내. 앱은 auth.signIn을 받으면 answer가 만든 결과를 같은 id로 돌려준다. */
function inApp(answer: (provider: string) => object, capabilities: string[] = ["auth.signIn"]) {
  const postMessage = jest.fn((message: string) => {
    const { payload } = JSON.parse(message) as { payload: { id: string; provider: string } };
    queueMicrotask(() =>
      window.dispatchEvent(
        new CustomEvent(BRIDGE_EVENT, {
          detail: {
            v: 1,
            type: "auth.signInResult",
            payload: { id: payload.id, provider: payload.provider, ...answer(payload.provider) },
          },
        }),
      ),
    );
  });
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

beforeEach(() => {
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { cookie: "__Host-XSRF-TOKEN=csrf-test" },
  });
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: Object.assign(new EventTarget(), { location: { origin: "https://dev.salmonbus.com" } }),
  });
});
afterEach(() => {
  global.fetch = originalFetch;
  Reflect.deleteProperty(globalThis, "document");
  Reflect.deleteProperty(globalThis, "window");
});

describe("앱 안 로그인", () => {
  it("브라우저에서는 서버에 묻지 않고 웹 로그인으로 보낸다", async () => {
    global.fetch = jest.fn<typeof fetch>();
    expect(await signInInApp("KAKAO", {})).toBe("unsupported");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("서버에 네이티브 로그인 API가 없으면(nonce 404) 앱을 부르지 않고 웹 로그인으로 보낸다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(notFound());
    const { bridgeWindow, postMessage } = inApp(() => ({ status: "cancelled" }));
    expect(await signInInApp("APPLE", bridgeWindow)).toBe("unsupported");
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/auth/apple/native/nonce",
      expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }) }),
    );
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("앱이 준 ID 토큰과 서버 nonce로 로그인한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, { nonce: NONCE }))
      .mockResolvedValueOnce(response(204));
    const { bridgeWindow, postMessage } = inApp(() => ({
      status: "success",
      idToken: "apple.id.token",
      authorizationCode: "apple-code",
    }));
    expect(await signInInApp("APPLE", bridgeWindow)).toBe("signedIn");
    expect(JSON.parse(postMessage.mock.calls[0]?.[0] ?? "null")).toMatchObject({
      type: "auth.signIn",
      payload: { provider: "apple", nonce: NONCE },
    });
    expect(global.fetch).toHaveBeenLastCalledWith(
      "/api/auth/apple/native",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ idToken: "apple.id.token", nonce: NONCE, authorizationCode: "apple-code" }),
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }),
      }),
    );
  });

  it("카카오는 인가 코드 없이 ID 토큰과 nonce만 보낸다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, { nonce: NONCE }))
      .mockResolvedValueOnce(response(204));
    const { bridgeWindow } = inApp(() => ({ status: "success", idToken: "kakao.id.token" }));
    expect(await signInInApp("KAKAO", bridgeWindow)).toBe("signedIn");
    expect(global.fetch).toHaveBeenLastCalledWith(
      "/api/auth/kakao/native",
      expect.objectContaining({ body: JSON.stringify({ idToken: "kakao.id.token", nonce: NONCE }) }),
    );
  });

  it.each([
    ["사용자가 취소하면", { status: "cancelled" }, "cancelled"],
    ["앱이 그 제공자로 로그인할 수 없으면", { status: "failed", reason: "unavailable" }, "unsupported"],
    ["SDK가 실패하면", { status: "failed", reason: "error" }, "failed"],
  ])("%s 서버 로그인을 부르지 않는다", async (_name, answer, outcome) => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(response(200, { nonce: NONCE }));
    const { bridgeWindow } = inApp(() => answer);
    expect(await signInInApp("KAKAO", bridgeWindow)).toBe(outcome);
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it("서버가 토큰을 거절하면 실패다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, { nonce: NONCE }))
      .mockResolvedValueOnce(
        response(401, { code: "UNAUTHENTICATED", message: "로그인이 필요합니다.", requestId: "r" }),
      );
    const { bridgeWindow } = inApp(() => ({ status: "success", idToken: "expired.token" }));
    expect(await signInInApp("KAKAO", bridgeWindow)).toBe("failed");
  });

  it.each([
    ["nonce 발급이 서버 오류일 때", () => response(500, { code: "INTERNAL_ERROR", message: "오류", requestId: "r" })],
    ["nonce가 앱이 받는 형식이 아닐 때", () => response(200, { nonce: "a+b/c=" })],
  ])("%s 앱을 부르지 않고 실패한다", async (_name, nonceResponse) => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(nonceResponse());
    const { bridgeWindow, postMessage } = inApp(() => ({ status: "cancelled" }));
    expect(await signInInApp("KAKAO", bridgeWindow)).toBe("failed");
    expect(postMessage).not.toHaveBeenCalled();
  });
});

describe("앱 안 Apple 회원 탈퇴", () => {
  it("다시 인증해서 받은 인가 코드로 탈퇴한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, { nonce: NONCE }))
      .mockResolvedValueOnce(response(204));
    const { bridgeWindow } = inApp(() => ({
      status: "success",
      idToken: "apple.id.token",
      authorizationCode: "apple-code",
    }));
    expect(await withdrawAppleInApp(bridgeWindow)).toBe("withdrawn");
    expect(global.fetch).toHaveBeenLastCalledWith(
      "/api/me/withdrawal/apple/native",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ authorizationCode: "apple-code" }) }),
    );
  });

  it("서버에 네이티브 API가 없으면 다시 인증하기 전에 웹 탈퇴로 보낸다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(notFound());
    const { bridgeWindow, postMessage } = inApp(() => ({ status: "cancelled" }));
    expect(await withdrawAppleInApp(bridgeWindow)).toBe("unsupported");
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("인가 코드가 없으면 탈퇴 API를 부르지 않는다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(response(200, { nonce: NONCE }));
    const { bridgeWindow } = inApp(() => ({ status: "success", idToken: "apple.id.token" }));
    expect(await withdrawAppleInApp(bridgeWindow)).toBe("failed");
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it("사용자가 다시 인증을 취소하면 취소다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValueOnce(response(200, { nonce: NONCE }));
    const { bridgeWindow } = inApp(() => ({ status: "cancelled" }));
    expect(await withdrawAppleInApp(bridgeWindow)).toBe("cancelled");
  });
});
