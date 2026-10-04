import { useEffect, useState } from "react";
import type { ApiResult } from "@/shared/api/client";
import { lastSuccessOf, type LastSuccess, type PolledResource } from "@/shared/api/polledResource";
import { fetchLiveVehicles } from "@/shared/api/routeForecast.api";
import type { LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";

export const DEFAULT_LIVE_MOTION_DURATION_MS = 20_000;
const MIN_LIVE_MOTION_DURATION_MS = 5_000;

function loadLiveVehicles(routeId: string, signal: AbortSignal): Promise<ApiResult<LiveVehicles>> {
  return fetchLiveVehicles(routeId, { signal });
}

export function useLiveVehicles(routeId: string) {
  const liveVehicleResource = usePolledRequest(loadLiveVehicles, routeId);
  const liveVehicles = useFreshLiveVehicles(lastSuccessOf(liveVehicleResource));
  const motionDurationMs = liveMotionDurationOf(liveVehicleResource);

  return { liveVehicles, motionDurationMs };
}

function useFreshLiveVehicles(lastSuccess: LastSuccess<LiveVehicles> | null): LiveVehicles | null {
  const staleAt = lastSuccess?.body.observation.staleAt ?? null;
  const staleAtMillis = staleAt === null ? null : Date.parse(staleAt);
  const serverClock = lastSuccess?.clock ?? null;
  const observationKey =
    lastSuccess === null || staleAt === null
      ? null
      : JSON.stringify([lastSuccess.body.routeId, lastSuccess.body.referenceVersionId, staleAt]);
  const [expiredObservationKey, setExpiredObservationKey] = useState<string | null>(null);

  useEffect(() => {
    if (observationKey === null || staleAtMillis === null) return;

    const referenceNow = serverClock?.now() ?? Date.now();
    const expiresInMs = Number.isNaN(staleAtMillis) ? 0 : Math.max(0, staleAtMillis - referenceNow);
    const expiryTimer = window.setTimeout(() => setExpiredObservationKey(observationKey), expiresInMs);

    return () => window.clearTimeout(expiryTimer);
  }, [observationKey, serverClock, staleAtMillis]);

  if (lastSuccess === null || staleAtMillis === null) return null;

  const referenceAtReceipt = lastSuccess.clock?.servedAt ?? lastSuccess.receivedAt;
  const expiredBeforeReceipt = Number.isNaN(staleAtMillis) || staleAtMillis <= referenceAtReceipt;

  return !expiredBeforeReceipt && expiredObservationKey !== observationKey ? lastSuccess.body : null;
}

function liveMotionDurationOf(resource: PolledResource<LiveVehicles>): number {
  if (resource.status !== "success" || resource.lifetime.noStore || resource.lifetime.maxAgeSeconds === null) {
    return DEFAULT_LIVE_MOTION_DURATION_MS;
  }

  const remainingFreshSeconds = Math.max(0, resource.lifetime.maxAgeSeconds - resource.lifetime.ageSeconds);
  return Math.max(MIN_LIVE_MOTION_DURATION_MS, remainingFreshSeconds * 1_000);
}
