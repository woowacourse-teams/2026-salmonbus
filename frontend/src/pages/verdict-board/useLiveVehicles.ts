import { useEffect, useState } from "react";
import type { ApiResult } from "@/shared/api/client";
import { fetchLiveVehicles } from "@/shared/api/routeForecast.api";
import type { LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest, type PolledResource } from "@/shared/api/usePolledRequest";

export const DEFAULT_LIVE_MOTION_DURATION_MS = 20_000;
const MIN_LIVE_MOTION_DURATION_MS = 5_000;

function loadLiveVehicles(routeId: string, signal: AbortSignal): Promise<ApiResult<LiveVehicles>> {
  return fetchLiveVehicles(routeId, { signal });
}

export function useLiveVehicles(routeId: string) {
  const liveVehicleResource = usePolledRequest(loadLiveVehicles, routeId);
  const liveVehicles = useFreshLiveVehicles(liveVehicleResource);
  const motionDurationMs = liveMotionDurationOf(liveVehicleResource.result);

  return { liveVehicles, motionDurationMs };
}

function useFreshLiveVehicles({
  body,
  clock: serverClock,
  receivedAt,
}: PolledResource<LiveVehicles>): LiveVehicles | null {
  const staleAt = body?.observation.staleAt ?? null;
  const staleAtMillis = staleAt === null ? null : Date.parse(staleAt);
  const observationKey =
    body === null || staleAt === null ? null : JSON.stringify([body.routeId, body.referenceVersionId, staleAt]);
  const [expiredObservationKey, setExpiredObservationKey] = useState<string | null>(null);

  useEffect(() => {
    if (observationKey === null || staleAtMillis === null) return;

    const referenceNow = serverClock?.now() ?? Date.now();
    const expiresInMs = Number.isNaN(staleAtMillis) ? 0 : Math.max(0, staleAtMillis - referenceNow);
    const expiryTimer = window.setTimeout(() => setExpiredObservationKey(observationKey), expiresInMs);

    return () => window.clearTimeout(expiryTimer);
  }, [observationKey, serverClock, staleAtMillis]);

  const referenceAtReceipt = serverClock?.servedAt ?? receivedAt;
  const expiredBeforeReceipt =
    staleAtMillis !== null &&
    (Number.isNaN(staleAtMillis) || (referenceAtReceipt !== null && staleAtMillis <= referenceAtReceipt));

  return body !== null && observationKey !== null && !expiredBeforeReceipt && expiredObservationKey !== observationKey
    ? body
    : null;
}

function liveMotionDurationOf(result: ApiResult<LiveVehicles> | null): number {
  if (result === null || !result.ok || result.lifetime.noStore || result.lifetime.maxAgeSeconds === null) {
    return DEFAULT_LIVE_MOTION_DURATION_MS;
  }

  const remainingFreshSeconds = Math.max(0, result.lifetime.maxAgeSeconds - result.lifetime.ageSeconds);
  return Math.max(MIN_LIVE_MOTION_DURATION_MS, remainingFreshSeconds * 1_000);
}
