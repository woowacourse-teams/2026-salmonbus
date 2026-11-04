import { createContext, useContext } from "react";
import type { ApiResult } from "@/shared/api/client";
import type { Member } from "@/shared/api/auth.types";

export type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; member: Member }
  | { status: "unavailable" }
  | { status: "error" };

interface AuthContextValue {
  state: AuthState;
  reload: () => Promise<void>;
  changeNickname: (nickname: string) => Promise<ApiResult<Member>>;
  signOut: () => Promise<ApiResult<void>>;
  withdraw: () => Promise<ApiResult<void | { startUrl: string }>>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (value === null) throw new Error("AuthProvider 안에서 사용해야 합니다.");
  return value;
}

/** 회원이면 회원 ID, 비회원이면 null이다. 세션을 확인하지 못한 상태는 회원인지 모르는 것이라 undefined다. */
export function memberIdOf(state: AuthState): string | null | undefined {
  if (state.status === "authenticated") return state.member.id;
  if (state.status === "anonymous") return null;
  return undefined;
}
