import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { ApiResult } from "@/shared/api/client";
import * as api from "@/shared/api/auth.api";
import type { AuthSession } from "@/shared/api/auth.types";
import { AuthContext, type AuthState } from "@/shared/hooks/useAuth";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });
  const revision = useRef(0);
  const mutations = useRef(0);
  const reload = useCallback(async () => {
    if (mutations.current > 0) return;
    const current = ++revision.current;
    const result = await api.fetchSession();
    if (current !== revision.current) return;
    setState(stateOf(result));
  }, []);

  useEffect(() => {
    const current = ++revision.current;
    void api.fetchSession().then((result) => {
      if (current === revision.current) setState(stateOf(result));
    });
    const onFocus = () => {
      void reload();
    };
    const onVisible = () => {
      if (document.visibilityState === "visible") void reload();
    };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      revision.current += 1;
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [reload]);

  async function changeNickname(nickname: string) {
    mutations.current += 1;
    const current = ++revision.current;
    const result = await api.updateNickname(nickname);
    mutations.current -= 1;
    if (current === revision.current) {
      if (result.ok) setState({ status: "authenticated", member: result.body });
      else if ("status" in result.failure && result.failure.status === 401) setState({ status: "anonymous" });
    }
    return result;
  }

  async function signOut() {
    mutations.current += 1;
    const current = ++revision.current;
    const result = await api.logout();
    mutations.current -= 1;
    if (current === revision.current && result.ok) setState({ status: "anonymous" });
    return result;
  }

  async function withdraw() {
    mutations.current += 1;
    const current = ++revision.current;
    const result = await api.withdraw();
    mutations.current -= 1;
    if (current === revision.current && result.ok && result.body === undefined) setState({ status: "anonymous" });
    if (current === revision.current && !result.ok && "status" in result.failure && result.failure.status === 401)
      setState({ status: "anonymous" });
    return result;
  }

  return <AuthContext value={{ state, reload, changeNickname, signOut, withdraw }}>{children}</AuthContext>;
}

function stateOf(result: ApiResult<AuthSession>): AuthState {
  if (result.ok)
    return result.body.authenticated
      ? { status: "authenticated", member: result.body.member }
      : { status: "anonymous" };
  return { status: "status" in result.failure && result.failure.status === 404 ? "unavailable" : "error" };
}
