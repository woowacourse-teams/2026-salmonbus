import type { ApiFailure, ApiResult } from "./client";
import type { CacheLifetime } from "./cacheControl";
import type { ReferenceClock } from "./referenceClock";

export interface LastSuccess<T> {
  body: T;
  clock: ReferenceClock | null;
  receivedAt: number;
}

export type PolledResource<T> =
  | { status: "pending" }
  | { status: "loadingError"; failure: ApiFailure }
  | { status: "success"; lastSuccess: LastSuccess<T>; lifetime: CacheLifetime }
  | { status: "refetchError"; failure: ApiFailure; lastSuccess: LastSuccess<T> };

export const PENDING_RESOURCE: PolledResource<never> = { status: "pending" };

export function nextResourceFrom<T>(
  previous: PolledResource<T>,
  result: ApiResult<T>,
  receivedAt: number,
): PolledResource<T> {
  if (result.ok) {
    return {
      status: "success",
      lastSuccess: { body: result.body, clock: result.clock, receivedAt },
      lifetime: result.lifetime,
    };
  }

  const lastSuccess = lastSuccessOf(previous);
  return lastSuccess === null
    ? { status: "loadingError", failure: result.failure }
    : { status: "refetchError", failure: result.failure, lastSuccess };
}

export function lastSuccessOf<T>(resource: PolledResource<T>): LastSuccess<T> | null {
  return resource.status === "success" || resource.status === "refetchError" ? resource.lastSuccess : null;
}
