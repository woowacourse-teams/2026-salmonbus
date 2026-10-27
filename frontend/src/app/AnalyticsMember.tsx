import { useEffect } from "react";
import { setAnalyticsMemberId } from "@/shared/analytics/analytics";
import { useAuth, type AuthState } from "@/shared/hooks/useAuth";

export function AnalyticsMember() {
  const { state } = useAuth();
  const memberId = memberIdOf(state);

  useEffect(() => {
    if (memberId !== undefined) setAnalyticsMemberId(memberId);
  }, [memberId]);
  return null;
}

// 세션을 확인하지 못한 상태는 회원인지 모르는 것이라 직전 식별을 그대로 둔다.
function memberIdOf(state: AuthState): string | null | undefined {
  if (state.status === "authenticated") return state.member.id;
  if (state.status === "anonymous") return null;
  return undefined;
}
