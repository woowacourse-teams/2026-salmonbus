import type { ApiFailure } from "@/shared/api/client";
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

export function nicknameErrorOf(input: string): string | null {
  const nickname = input.trim();
  if (nickname.length < 2 || nickname.length > 10) return "닉네임은 2자 이상 10자 이하로 입력해 주세요.";
  if (!/^[가-힣a-zA-Z0-9]+$/.test(nickname)) return "닉네임은 한글, 영문, 숫자만 사용할 수 있어요.";
  return null;
}

export function authErrorOf(failure: ApiFailure): string {
  if (failure.kind === "contract") {
    switch (failure.error.code) {
      case "NICKNAME_TAKEN":
        return "이미 사용 중인 닉네임이에요.";
      case "NICKNAME_LENGTH":
        return "닉네임은 2자 이상 10자 이하로 입력해 주세요.";
      case "NICKNAME_CHARACTERS":
        return "닉네임은 한글, 영문, 숫자만 사용할 수 있어요.";
      case "NICKNAME_RESERVED":
        return "사용할 수 없는 닉네임이에요. 다른 닉네임을 입력해 주세요.";
      case "CSRF_REJECTED":
        return "요청을 확인하지 못했어요. 새로고침한 뒤 다시 시도해 주세요.";
      case "UNAUTHENTICATED":
        return "로그인이 만료됐어요. 다시 로그인해 주세요.";
    }
  }
  if ("status" in failure && failure.status === 404)
    return "로그인 기능을 준비 중이에요. 노선 검색은 계속 이용할 수 있어요.";
  return "요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.";
}

export function boardReturnTo(candidate: string): string {
  if (candidate === "/") return candidate;
  const [path, query] = candidate.split("?");
  if (!path || !/^\/routes\/\d{9}$/.test(path)) return "/";
  if (query === undefined) return path;
  const parameters = query.split("&");
  const names = parameters.map((parameter) => parameter.split("=")[0]);
  return new Set(names).size === parameters.length &&
    parameters.every((parameter) => /^(direction=(UP|DOWN)|stopId=[A-Za-z0-9_-]{1,40})$/.test(parameter)) &&
    candidate.split("?").length === 2
    ? candidate
    : "/";
}

export function localReturnTo(candidate: string | null): string {
  if (candidate === "/mypage") return candidate;
  if (candidate && /^\/\?q=\d{1,20}$/.test(candidate)) return candidate;
  return boardReturnTo(candidate ?? "/");
}
