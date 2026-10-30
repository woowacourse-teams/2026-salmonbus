import type { BridgeWindow } from "../bridge/bridge";
import { issueNativeNonce, signInWithIdToken, withdrawAppleWithCode } from "../api/auth.api";
import type { LoginProvider } from "../api/auth.types";
import type { ApiFailure } from "../api/client";
import { isNativeSignInSupported, requestNativeSignIn } from "./nativeAuth";
import type { NativeLoginProvider } from "./nativeAuth.types";

/** unsupported면 웹 로그인(WebView 안 제공자 화면)으로 간다. */
export type InAppSignInOutcome = "signedIn" | "cancelled" | "failed" | "unsupported";
export type InAppWithdrawalOutcome = "withdrawn" | "cancelled" | "failed" | "unsupported";

const NATIVE_PROVIDERS = { KAKAO: "kakao", APPLE: "apple" } as const satisfies Record<
  LoginProvider,
  NativeLoginProvider
>;

/**
 * 앱 SDK로 로그인한다. 앱이 auth.signIn을 모르거나, 서버에 네이티브 로그인 API가 없거나(nonce 404),
 * 앱이 그 제공자로 로그인할 수 없다고 답하면 unsupported다.
 * 서버 API가 있는지는 설정값 대신 nonce 응답으로 본다. dev와 운영에 API가 배포되는 대로 따로 켜진다.
 */
export async function signInInApp(provider: LoginProvider, bridgeWindow?: BridgeWindow): Promise<InAppSignInOutcome> {
  if (!isNativeSignInSupported(bridgeWindow)) return "unsupported";
  const nativeProvider = NATIVE_PROVIDERS[provider];
  const issued = await issueNativeNonce(nativeProvider);
  if (!issued.ok) return isNotFound(issued.failure) ? "unsupported" : "failed";
  const { nonce } = issued.body;
  const result = await requestNativeSignIn(nativeProvider, nonce, bridgeWindow);
  if (result.status === "cancelled") return "cancelled";
  if (result.status === "failed") return result.reason === "unavailable" ? "unsupported" : "failed";
  const credential =
    result.authorizationCode === undefined
      ? { idToken: result.idToken, nonce }
      : { idToken: result.idToken, nonce, authorizationCode: result.authorizationCode };
  const signedIn = await signInWithIdToken(nativeProvider, credential);
  return signedIn.ok ? "signedIn" : "failed";
}

/**
 * Apple 회원 탈퇴. 앱 바텀시트로 다시 인증해서 받은 인가 코드를 서버에 보낸다.
 * 탈퇴 API가 있는지 미리 알 방법이 없어서, 같은 SAL-318로 배포되는 nonce API가 있는지로 본다.
 * 인증을 먼저 하고 API가 없다는 것을 알면 사용자가 웹 화면에서 한 번 더 인증해야 한다.
 */
export async function withdrawAppleInApp(bridgeWindow?: BridgeWindow): Promise<InAppWithdrawalOutcome> {
  if (!isNativeSignInSupported(bridgeWindow)) return "unsupported";
  const issued = await issueNativeNonce("apple");
  if (!issued.ok) return isNotFound(issued.failure) ? "unsupported" : "failed";
  const result = await requestNativeSignIn("apple", issued.body.nonce, bridgeWindow);
  if (result.status === "cancelled") return "cancelled";
  if (result.status === "failed") return result.reason === "unavailable" ? "unsupported" : "failed";
  if (result.authorizationCode === undefined) return "failed";
  const withdrawn = await withdrawAppleWithCode(result.authorizationCode);
  return withdrawn.ok ? "withdrawn" : "failed";
}

function isNotFound(failure: ApiFailure): boolean {
  return "status" in failure && failure.status === 404;
}
