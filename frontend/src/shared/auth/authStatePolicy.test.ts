import { describe, expect, it } from "@jest/globals";
import type { AuthSession, Member } from "@/shared/api/auth.types";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import type { AuthState } from "@/shared/hooks/useAuth";
import { nextAuthState } from "./authStatePolicy";

const member: Member = { id: "member-1", nickname: "살몽", provider: "KAKAO" };
const signedIn: AuthState = { status: "authenticated", member };

function answered(body: AuthSession): ApiResult<AuthSession> {
  return {
    ok: true,
    body,
    status: 200,
    requestId: null,
    clock: null,
    lifetime: { noStore: true, maxAgeSeconds: null, ageSeconds: 0 },
  };
}

function failedWith(failure: ApiFailure): ApiResult<AuthSession> {
  return { ok: false, failure };
}

const serverError: ApiFailure = { kind: "malformed", status: 503, requestId: null };
const notFound: ApiFailure = { kind: "malformed", status: 404, requestId: null };

describe("세션 조회 결과에 따른 로그인 상태", () => {
  it.each<[string, ApiFailure]>([
    ["네트워크가 끊겼을 때", { kind: "network", cause: new TypeError("Load failed") }],
    ["응답이 제한 시간을 넘겼을 때", { kind: "timeout" }],
    ["서버가 503으로 답했을 때", serverError],
  ])("회원은 %s 회원으로 남는다", (_, failure) => {
    expect(nextAuthState(signedIn, failedWith(failure))).toBe(signedIn);
  });

  it.each<AuthState>([{ status: "loading" }, { status: "anonymous" }, { status: "error" }])(
    "회원이 아닌 상태($status)에서 조회가 실패하면 확인 실패로 둔다",
    (previous) => {
      expect(nextAuthState(previous, failedWith(serverError))).toEqual({ status: "error" });
    },
  );

  it("서버가 비회원이라고 답하면 회원이었어도 비회원이 된다", () => {
    expect(nextAuthState(signedIn, answered({ authenticated: false, refreshable: false }))).toEqual({
      status: "anonymous",
    });
  });

  it("서버가 회원이라고 답하면 받은 회원 정보로 바꾼다", () => {
    const renamed: Member = { ...member, nickname: "연어" };
    expect(nextAuthState(signedIn, answered({ authenticated: true, refreshable: false, member: renamed }))).toEqual({
      status: "authenticated",
      member: renamed,
    });
  });

  it("세션 API가 없으면(404) 로그인 기능 없음으로 둔다", () => {
    expect(nextAuthState({ status: "loading" }, failedWith(notFound))).toEqual({ status: "unavailable" });
  });
});
