import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router";
import { track, trackAsMember } from "@/shared/analytics/track";
import { useAuth } from "@/shared/hooks/useAuth";
import { clearLoginReturn, readLoginReturn } from "@/shared/routing/loginReturn";

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
          notice: withdrawn
            ? "회원탈퇴가 완료됐어요"
            : withdrawal === "cancelled"
              ? "회원탈퇴를 취소했어요"
              : "회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요.",
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
