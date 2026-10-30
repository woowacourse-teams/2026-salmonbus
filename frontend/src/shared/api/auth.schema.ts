import type { AuthSession, Member } from "./auth.types";

export function isMember(value: unknown): value is Member {
  if (typeof value !== "object" || value === null) return false;
  const member = value as Record<string, unknown>;
  return (
    typeof member.id === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(member.id) &&
    typeof member.nickname === "string" &&
    (member.provider === "KAKAO" || member.provider === "APPLE")
  );
}

export function isAuthSession(value: unknown): value is AuthSession {
  if (typeof value !== "object" || value === null) return false;
  const session = value as Record<string, unknown>;
  return session.authenticated === true
    ? session.refreshable === false && isMember(session.member)
    : session.authenticated === false && typeof session.refreshable === "boolean" && session.member === undefined;
}

// 앱 app/auth/authMessages.ts의 NONCE와 같은 규칙이다. 앱은 이 규칙에 맞지 않는 요청을 버린다.
const NATIVE_NONCE = /^[A-Za-z0-9._~-]{16,128}$/;

export function isNativeNonce(value: unknown): value is { nonce: string } {
  if (typeof value !== "object" || value === null) return false;
  const nonce = (value as Record<string, unknown>).nonce;
  return typeof nonce === "string" && NATIVE_NONCE.test(nonce);
}
