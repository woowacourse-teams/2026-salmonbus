import { fetchSession, logout, updateNickname, withdraw } from "../api/auth.api";
import type { AuthSession, Member } from "../api/auth.types";
import type { ApiResult } from "../api/client";
import {
  allowDeviceRegistration,
  registerDeviceIfGranted,
  unregisterThisDevice,
} from "../notification/useNotificationRegistration";

export type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; member: Member }
  | { status: "unavailable" }
  | { status: "error" };

export interface AuthServices {
  fetchSession: () => Promise<ApiResult<AuthSession>>;
  updateNickname: (nickname: string) => Promise<ApiResult<Member>>;
  logout: () => Promise<ApiResult<void>>;
  withdraw: () => Promise<ApiResult<void | { startUrl: string }>>;
  unregisterThisDevice: () => Promise<void>;
  allowDeviceRegistration: () => void;
  registerDeviceIfGranted: () => Promise<void>;
}

interface VisibilitySource extends EventTarget {
  readonly visibilityState: DocumentVisibilityState;
}

export function createAuthStore(services: AuthServices) {
  let state: AuthState = { status: "loading" };
  let revision = 0;
  let mutations = 0;
  const listeners = new Set<() => void>();

  function setState(next: AuthState) {
    if (isSameAuthState(state, next)) return;
    state = next;
    listeners.forEach((listener) => listener());
  }

  function subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }

  function authState(): AuthState {
    return state;
  }

  async function reload(): Promise<void> {
    if (mutations > 0) return;
    const current = ++revision;
    const result = await services.fetchSession();
    if (current !== revision) return;
    setState(stateOf(result));
  }

  async function changeNickname(nickname: string) {
    mutations += 1;
    const current = ++revision;
    const result = await services.updateNickname(nickname);
    mutations -= 1;
    if (current === revision) {
      if (result.ok) setState({ status: "authenticated", member: result.body });
      else if ("status" in result.failure && result.failure.status === 401) setState({ status: "anonymous" });
    }
    return result;
  }

  async function signOut() {
    mutations += 1;
    const current = ++revision;
    await services.unregisterThisDevice();
    const result = await services.logout();
    mutations -= 1;
    if (current === revision && result.ok) setState({ status: "anonymous" });
    if (current === revision && !result.ok) {
      services.allowDeviceRegistration();
      void services.registerDeviceIfGranted();
    }
    return result;
  }

  async function withdraw() {
    mutations += 1;
    const current = ++revision;
    const result = await services.withdraw();
    mutations -= 1;
    if (current === revision && result.ok && result.body === undefined) setState({ status: "anonymous" });
    if (current === revision && !result.ok && "status" in result.failure && result.failure.status === 401)
      setState({ status: "anonymous" });
    return result;
  }

  function init(windowEvents: EventTarget = window, page: VisibilitySource = document): () => void {
    void reload();
    const onFocus = () => {
      void reload();
    };
    const onVisible = () => {
      if (page.visibilityState === "visible") void reload();
    };
    windowEvents.addEventListener("focus", onFocus);
    page.addEventListener("visibilitychange", onVisible);
    return () => {
      revision += 1;
      windowEvents.removeEventListener("focus", onFocus);
      page.removeEventListener("visibilitychange", onVisible);
    };
  }

  return { subscribe, authState, reload, changeNickname, signOut, withdraw, init };
}

export const authStore = createAuthStore({
  fetchSession,
  updateNickname,
  logout,
  withdraw,
  unregisterThisDevice,
  allowDeviceRegistration,
  registerDeviceIfGranted,
});

export const initAuth = authStore.init;

function stateOf(result: ApiResult<AuthSession>): AuthState {
  if (result.ok)
    return result.body.authenticated
      ? { status: "authenticated", member: result.body.member }
      : { status: "anonymous" };
  return { status: "status" in result.failure && result.failure.status === 404 ? "unavailable" : "error" };
}

function isSameAuthState(a: AuthState, b: AuthState): boolean {
  if (a.status !== b.status) return false;
  if (a.status !== "authenticated" || b.status !== "authenticated") return true;
  return isSameMember(a.member, b.member);
}

function isSameMember(a: Member, b: Member): boolean {
  const same: Record<keyof Member, boolean> = {
    id: a.id === b.id,
    nickname: a.nickname === b.nickname,
    provider: a.provider === b.provider,
  };
  return Object.values(same).every(Boolean);
}
