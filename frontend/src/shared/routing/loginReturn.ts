import type { LoginProvider } from "@/shared/api/auth.types";
import { localReturnTo } from "./loginReturnPolicy";

const STORAGE_KEY = "salmonbus.loginReturn";
const TTL = 10 * 60_000;

export interface LoginReturn {
  returnTo: string;
  provider: LoginProvider;
  createdAt: number;
}

export function saveLoginReturn(returnTo: string, provider: LoginProvider): void {
  try {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ returnTo: localReturnTo(returnTo), provider, createdAt: Date.now() }),
    );
  } catch {
    /* 복귀 경로를 저장할 수 없으면 서버가 지정한 화면으로 돌아간다. */
  }
}

export function readLoginReturn(): LoginReturn | null {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? "null");
    if (typeof value !== "object" || value === null) return null;
    const candidate = value as Record<string, unknown>;
    if (
      (candidate.provider !== "KAKAO" && candidate.provider !== "APPLE") ||
      typeof candidate.returnTo !== "string" ||
      typeof candidate.createdAt !== "number" ||
      candidate.createdAt > Date.now() ||
      Date.now() - candidate.createdAt >= TTL
    )
      return null;
    return {
      returnTo: localReturnTo(candidate.returnTo),
      provider: candidate.provider,
      createdAt: candidate.createdAt,
    };
  } catch {
    return null;
  }
}

export function clearLoginReturn(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* 저장소를 사용할 수 없어도 화면 이동은 계속한다. */
  }
}
