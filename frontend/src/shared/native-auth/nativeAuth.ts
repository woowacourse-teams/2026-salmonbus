import { isSupportedInApp, sendToApp, subscribeToApp, type BridgeWindow } from "../bridge/bridge";
import type { NativeLoginProvider, NativeSignInFailureReason, NativeSignInResult } from "./nativeAuth.types";

const FAILURE_REASONS: readonly NativeSignInFailureReason[] = ["unavailable", "busy", "error"];

/** 앱이 네이티브 로그인을 받을 수 있는지. 브라우저와 옛 앱은 false다. */
export function isNativeSignInSupported(bridgeWindow?: BridgeWindow): boolean {
  return isSupportedInApp("auth.signIn", bridgeWindow);
}

/** 앱에 로그인 창을 열게 하고, 같은 id로 온 결과를 기다린다. 앱은 성공, 취소, 실패 중 하나를 반드시 돌려준다. */
export function requestNativeSignIn(
  provider: NativeLoginProvider,
  nonce: string,
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NativeSignInResult> {
  const id = crypto.randomUUID();
  return new Promise((resolve) => {
    const unsubscribe = subscribeToApp((message) => {
      if (message.type !== "auth.signInResult") return;
      const result = nativeSignInResultFrom(message.payload);
      if (result === null || result.id !== id) return;
      unsubscribe();
      resolve(result);
    }, target);
    sendToApp("auth.signIn", { id, provider, nonce }, bridgeWindow);
  });
}

export function nativeSignInResultFrom(payload: object): NativeSignInResult | null {
  const value = payload as Record<string, unknown>;
  const { id, provider, status } = value;
  if (typeof id !== "string" || (provider !== "apple" && provider !== "kakao")) return null;
  if (status === "cancelled") return { id, provider, status };
  if (status === "failed") {
    // 앱이 새 이유를 보내도 실패로 다룬다.
    const reason = FAILURE_REASONS.find((known) => known === value.reason) ?? "error";
    return { id, provider, status, reason };
  }
  if (status !== "success" || typeof value.idToken !== "string" || value.idToken === "") return null;
  if (value.authorizationCode !== undefined && typeof value.authorizationCode !== "string") return null;
  return value.authorizationCode === undefined
    ? { id, provider, status, idToken: value.idToken }
    : { id, provider, status, idToken: value.idToken, authorizationCode: value.authorizationCode };
}
