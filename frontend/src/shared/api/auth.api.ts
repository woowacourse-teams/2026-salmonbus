import { requestJson, type ApiResult, type RequestOptions } from "./client";
import { isAuthSession, isMember } from "./auth.schema";
import type { AuthSession, Member } from "./auth.types";

let generation = 0;
let refreshing: Promise<ApiResult<void>> | null = null;

function csrfToken(): string | null {
  return (
    document.cookie
      .split(";")
      .map((cookie) => cookie.trim())
      .find((cookie) => cookie.startsWith("__Host-XSRF-TOKEN="))
      ?.slice(18) ?? null
  );
}

async function authRequest<T>(url: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  if (options.method !== undefined && options.method !== "GET") {
    if (csrfToken() === null) {
      const session = await requestJson<AuthSession>("/api/auth/session");
      if (!session.ok) return session;
    }
    const token = csrfToken();
    if (token === null) return { ok: false, failure: { kind: "malformed", status: 200, requestId: null } };
    return requestJson<T>(url, { ...options, headers: { ...options.headers, "X-XSRF-TOKEN": token } });
  }
  return requestJson<T>(url, options);
}

export function refreshAuth(): Promise<ApiResult<void>> {
  if (refreshing !== null) return refreshing;
  refreshing = authRequest<void>("/api/auth/refresh", { method: "POST" }).finally(() => {
    refreshing = null;
  });
  return refreshing;
}

export async function fetchSession(): Promise<ApiResult<AuthSession>> {
  const started = generation;
  let result = await requestJson<unknown>("/api/auth/session");
  if (
    result.ok &&
    isAuthSession(result.body) &&
    !result.body.authenticated &&
    result.body.refreshable &&
    started === generation
  ) {
    const refreshed = await refreshAuth();
    if (!refreshed.ok) {
      if ("status" in refreshed.failure && refreshed.failure.status === 401) {
        return { ...result, body: { authenticated: false, refreshable: false } };
      }
      return refreshed;
    }
    result = await requestJson<unknown>("/api/auth/session");
  }
  return checked(result, isAuthSession);
}

export async function memberRequest<T>(url: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  const started = generation;
  let result = await authRequest<T>(url, options);
  if (!result.ok && "status" in result.failure && result.failure.status === 401 && started === generation) {
    const refreshed = await refreshAuth();
    if (!refreshed.ok) return refreshed;
    if (started !== generation) return { ok: false, failure: { kind: "aborted" } };
    result = await authRequest<T>(url, options);
  }
  return result;
}

export async function updateNickname(nickname: string): Promise<ApiResult<Member>> {
  return checked(
    await memberRequest<unknown>("/api/me", { method: "PATCH", body: { nickname: nickname.trim() } }),
    isMember,
  );
}

export async function logout(): Promise<ApiResult<void>> {
  generation += 1;
  await refreshing;
  return authRequest<void>("/api/auth/logout", { method: "POST" });
}

export async function withdraw(): Promise<ApiResult<void | { startUrl: string }>> {
  generation += 1;
  await refreshing;
  const result = await memberRequest<void>("/api/me", { method: "DELETE" });
  if (result.ok || result.failure.kind !== "contract" || result.failure.error.code !== "REAUTH_REQUIRED") return result;
  const start = await memberRequest<unknown>("/api/me/withdrawal/apple", { method: "POST", body: { returnTo: "/" } });
  return checked(start, (value): value is { startUrl: string } => {
    if (typeof value !== "object" || value === null || !("startUrl" in value) || typeof value.startUrl !== "string")
      return false;
    if (!value.startUrl.startsWith("/api/auth/apple/start?")) return false;
    const url = new URL(value.startUrl, window.location.origin);
    return (
      url.origin === window.location.origin &&
      url.pathname === "/api/auth/apple/start" &&
      url.searchParams.get("intent") === "withdraw" &&
      Boolean(url.searchParams.get("ticket"))
    );
  });
}

function checked<T>(result: ApiResult<unknown>, guard: (value: unknown) => value is T): ApiResult<T> {
  if (!result.ok) return result;
  return guard(result.body)
    ? { ...result, body: result.body }
    : {
        ok: false,
        failure: { kind: "malformed", status: result.status, requestId: result.requestId },
      };
}
