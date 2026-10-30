/** 앱이 Apple, 카카오 SDK로 로그인한다. 앱 app/auth/authMessages.ts가 받는 목록과 같아야 한다. */
export type NativeLoginProvider = "apple" | "kakao";

export interface AuthCommands {
  "auth.signIn": { id: string; provider: NativeLoginProvider; nonce: string };
}

/** unavailable: 이 앱에서 그 제공자로 로그인할 수 없다(카카오 키 없음 등). 웹은 WebView 로그인으로 간다. */
export type NativeSignInFailureReason = "unavailable" | "busy" | "error";

/** id는 auth.signIn의 id다. authorizationCode는 Apple만 준다. */
export type NativeSignInResult =
  | { id: string; provider: NativeLoginProvider; status: "success"; idToken: string; authorizationCode?: string }
  | { id: string; provider: NativeLoginProvider; status: "cancelled" }
  | { id: string; provider: NativeLoginProvider; status: "failed"; reason: NativeSignInFailureReason };

export interface AuthEvents {
  "auth.signInResult": NativeSignInResult;
}
