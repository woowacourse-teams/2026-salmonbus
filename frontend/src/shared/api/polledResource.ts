import type { ApiFailure, ApiResult } from "./client";
import type { CacheLifetime } from "./cacheControl";
import type { ReferenceClock } from "./referenceClock";

export interface LatestSuccess<T> {
  body: T;
  clock: ReferenceClock | null;
  receivedAt: number;
}

export type PolledResource<T> =
  | { status: "pending" }
  | { status: "loadingError"; failure: ApiFailure }
  | { status: "success"; latestSuccess: LatestSuccess<T>; lifetime: CacheLifetime }
  | { status: "refetchError"; failure: ApiFailure; latestSuccess: LatestSuccess<T> };

export const PENDING_RESOURCE: PolledResource<never> = { status: "pending" };

export function nextResourceFrom<T>(
  previous: PolledResource<T>,
  result: ApiResult<T>,
  receivedAt: number,
): PolledResource<T> {
  if (result.ok) {
    return {
      status: "success",
      latestSuccess: { body: result.body, clock: result.clock, receivedAt },
      lifetime: result.lifetime,
    };
  }

  const latestSuccess = latestSuccessOf(previous);
  return latestSuccess === null
    ? { status: "loadingError", failure: result.failure }
    : { status: "refetchError", failure: result.failure, latestSuccess };
}

export function latestSuccessOf<T>(resource: PolledResource<T>): LatestSuccess<T> | null {
  return resource.status === "success" || resource.status === "refetchError" ? resource.latestSuccess : null;
}
