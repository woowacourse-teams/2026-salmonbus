/** 실패하거나 취소한 로그인을 로그인 화면이 안내하도록, 처음 가려던 곳과 결과를 주소에 담는다. */
export function loginResultPathFor(result: "failed" | "cancelled", returnTo: string | undefined): string {
  return `/login?${new URLSearchParams({ returnTo: returnTo ?? "/", login: result })}`;
}
