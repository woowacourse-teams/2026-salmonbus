import type { AuthSession } from "@/shared/api/auth.types";
import type { ApiResult } from "@/shared/api/client";
import type { AuthState } from "@/shared/hooks/useAuth";

export function nextAuthState(previous: AuthState, result: ApiResult<AuthSession>): AuthState {
  if (result.ok)
    return result.body.authenticated
      ? { status: "authenticated", member: result.body.member }
      : { status: "anonymous" };
  if ("status" in result.failure && result.failure.status === 404) return { status: "unavailable" };
  return previous.status === "authenticated" ? previous : { status: "error" };
}
