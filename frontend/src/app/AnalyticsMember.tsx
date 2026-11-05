import { useEffect } from "react";
import { setAnalyticsMemberId } from "@/shared/analytics/analytics";
import { memberIdOf, useAuth } from "@/shared/hooks/useAuth";

export function AnalyticsMember() {
  const { state } = useAuth();
  const memberId = memberIdOf(state);

  useEffect(() => {
    if (memberId !== undefined) setAnalyticsMemberId(memberId);
  }, [memberId]);
  return null;
}
