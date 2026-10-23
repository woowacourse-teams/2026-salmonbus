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
