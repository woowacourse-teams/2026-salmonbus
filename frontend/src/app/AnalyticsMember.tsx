import { useEffect } from "react";
import { setAnalyticsMemberId } from "@/shared/analytics/analytics";
import { memberIdOf, useAuth } from "@/shared/hooks/useAuth";

export function AnalyticsMember() {
  const { state } = useAuth();
  const memberId = memberIdOf(state);

  // 회원인지 모르는 동안에는 직전 식별을 그대로 둔다.
  useEffect(() => {
    if (memberId !== undefined) setAnalyticsMemberId(memberId);
  }, [memberId]);
  return null;
}
