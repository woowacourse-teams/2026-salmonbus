import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router";
import { track, trackAsMember } from "@/shared/analytics/track";
import { useAuth } from "@/shared/hooks/useAuth";
import { clearLoginReturn, readLoginReturn } from "@/shared/routing/loginReturn";
import { withdrawalNoticeLabelOf } from "./withdrawalNoticeLabelOf";

export function AuthReturn() {
  const { state } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const handledReturnKey = useRef<string | null>(null);
  const memberId = state.status === "authenticated" ? state.member.id : null;

  useEffect(() => {
    if (location.pathname === "/login" || handledReturnKey.current === location.key) return;
    const query = new URLSearchParams(location.search);
    const login = query.get("login");
    const withdrawal = query.get("withdraw");
    const withdrawn = query.get("withdrawn") === "1";
    const pending = readLoginReturn();
    if (login === "failed" || login === "cancelled") {
      handledReturnKey.current = location.key;
      track("login_finished", { provider: pending?.provider ?? "unknown", result: login });
      clearLoginReturn();
      void navigate(`/login?${new URLSearchParams({ returnTo: pending?.returnTo ?? "/", login })}`, { replace: true });
    } else if (withdrawn || withdrawal === "failed" || withdrawal === "cancelled") {
      handledReturnKey.current = location.key;
      clearLoginReturn();
      void navigate(withdrawn ? "/" : "/mypage", {
        replace: true,
        state: {
          notice: withdrawalNoticeLabelOf(
            withdrawn ? "withdrawn" : withdrawal === "cancelled" ? "cancelled" : "failed",
          ),
        },
      });
    } else if (pending !== null && memberId !== null) {
      // 시작 기록이 없는 성공은 보내지 않는다. 서버의 성공 리다이렉트에는 결과 쿼리가 없어서 재방문과 구분할 수 없다.
      handledReturnKey.current = location.key;
      trackAsMember(memberId, "login_finished", { provider: pending.provider, result: "succeeded" });
      clearLoginReturn();
      void navigate(pending.returnTo, { replace: true });
    }
  }, [location.key, location.pathname, location.search, memberId, navigate]);
  return null;
}
