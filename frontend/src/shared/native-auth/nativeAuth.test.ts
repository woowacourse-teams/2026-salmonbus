import { describe, expect, it, jest } from "@jest/globals";
import { BRIDGE_EVENT } from "../bridge/bridge";
import { isNativeSignInSupported, nativeSignInResultFrom, requestNativeSignIn } from "./nativeAuth";

const NONCE = "q8Kf2-Lm9_xYz.0123~abcd";

function inAppWindow(capabilities: string[] = ["auth.signIn"]) {
  const postMessage = jest.fn<(message: string) => void>();
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

function sentRequest(postMessage: jest.Mock<(message: string) => void>) {
  const [message] = postMessage.mock.calls.at(0) ?? ["null"];
  return JSON.parse(message) as { type: string; payload: { id: string; provider: string; nonce: string } };
}

function reply(target: EventTarget, payload: object) {
  target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: "auth.signInResult", payload } }));
}

describe("네이티브 로그인 지원 판별", () => {
  it("capabilities에 auth.signIn이 있을 때만 지원한다", () => {
    expect(isNativeSignInSupported(inAppWindow().bridgeWindow)).toBe(true);
    expect(isNativeSignInSupported(inAppWindow(["haptic.impact"]).bridgeWindow)).toBe(false);
    expect(isNativeSignInSupported({})).toBe(false);
  });
});

describe("네이티브 로그인 요청", () => {
  it("요청을 보내고 같은 id로 온 결과만 받는다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    const signIn = requestNativeSignIn("apple", NONCE, bridgeWindow, target);
    const request = sentRequest(postMessage);
    expect(request.type).toBe("auth.signIn");
    expect(request.payload).toMatchObject({ provider: "apple", nonce: NONCE });

    reply(target, { id: "other", provider: "apple", status: "cancelled" });
    reply(target, {
      id: request.payload.id,
      provider: "apple",
      status: "success",
      idToken: "id.token",
      authorizationCode: "code",
    });

    await expect(signIn).resolves.toEqual({
      id: request.payload.id,
      provider: "apple",
      status: "success",
      idToken: "id.token",
      authorizationCode: "code",
    });
  });
});

describe("앱이 보낸 로그인 결과 읽기", () => {
  it("성공, 취소, 실패를 읽는다", () => {
    expect(nativeSignInResultFrom({ id: "a", provider: "kakao", status: "success", idToken: "t" })).toEqual({
      id: "a",
      provider: "kakao",
      status: "success",
      idToken: "t",
    });
    expect(nativeSignInResultFrom({ id: "a", provider: "kakao", status: "cancelled" })).toEqual({
      id: "a",
      provider: "kakao",
      status: "cancelled",
    });
    expect(nativeSignInResultFrom({ id: "a", provider: "apple", status: "failed", reason: "unavailable" })).toEqual({
      id: "a",
      provider: "apple",
      status: "failed",
      reason: "unavailable",
    });
  });

  it("모르는 실패 이유는 error로 읽는다", () => {
    expect(nativeSignInResultFrom({ id: "a", provider: "apple", status: "failed", reason: "newReason" })).toMatchObject(
      {
        reason: "error",
      },
    );
  });

  it.each([
    ["성공인데 ID 토큰이 없을 때", { id: "a", provider: "apple", status: "success" }],
    ["ID 토큰이 비었을 때", { id: "a", provider: "apple", status: "success", idToken: "" }],
    [
      "인가 코드가 문자열이 아닐 때",
      { id: "a", provider: "apple", status: "success", idToken: "t", authorizationCode: 1 },
    ],
    ["모르는 제공자일 때", { id: "a", provider: "google", status: "cancelled" }],
    ["id가 없을 때", { provider: "apple", status: "cancelled" }],
    ["모르는 상태일 때", { id: "a", provider: "apple", status: "pending" }],
  ])("%s 버린다", (_name, payload) => {
    expect(nativeSignInResultFrom(payload)).toBeNull();
  });
});
