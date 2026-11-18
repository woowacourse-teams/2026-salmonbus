import { useSyncExternalStore, type ReactNode } from "react";
import { authStore } from "@/shared/auth/authStore";
import { AuthContext } from "@/shared/hooks/useAuth";

const { subscribe, authState, reload, changeNickname, signOut, withdraw } = authStore;

export function AuthProvider({ children }: { children: ReactNode }) {
  const state = useSyncExternalStore(subscribe, authState);
  return <AuthContext value={{ state, reload, changeNickname, signOut, withdraw }}>{children}</AuthContext>;
}
