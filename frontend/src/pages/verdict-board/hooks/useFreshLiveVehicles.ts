import { useEffect, useState } from "react";
import type { LatestSuccess, PolledResource } from "@/shared/api/polledResource";
import type { RouteWatch, Vehicle } from "@/shared/api/routeForecast.types";

export const DEFAULT_LIVE_MOTION_DURATION_MS = 20_000;
const MIN_LIVE_MOTION_DURATION_MS = 5_000;
// 렌더마다 새 빈 배열을 만들면 마커 계산과 이동 효과가 매번 다시 돈다.
const NO_VEHICLES: readonly Vehicle[] = [];

export function useFreshLiveVehicles(latestSuccess: LatestSuccess<RouteWatch> | null): readonly Vehicle[] {
  const watch = latestSuccess?.body ?? null;
  const staleAtMillis = watch === null ? null : Date.parse(watch.staleAt);
  const serverClock = latestSuccess?.clock ?? null;
  const observationKey =
    watch === null ? null : JSON.stringify([watch.route.id, watch.route.referenceVersionId, watch.staleAt]);
  const [expiredObservationKey, setExpiredObservationKey] = useState<string | null>(null);

  useEffect(() => {
    if (observationKey === null || staleAtMillis === null) return;

    const referenceNow = serverClock?.now() ?? Date.now();
    const expiresInMs = Number.isNaN(staleAtMillis) ? 0 : Math.max(0, staleAtMillis - referenceNow);
    const expiryTimer = window.setTimeout(() => setExpiredObservationKey(observationKey), expiresInMs);

    return () => window.clearTimeout(expiryTimer);
  }, [observationKey, serverClock, staleAtMillis]);

  if (latestSuccess === null || staleAtMillis === null) return NO_VEHICLES;

  const referenceAtReceipt = latestSuccess.clock?.servedAt ?? latestSuccess.receivedAt;
  const expiredBeforeReceipt = Number.isNaN(staleAtMillis) || staleAtMillis <= referenceAtReceipt;

  return !expiredBeforeReceipt && expiredObservationKey !== observationKey ? latestSuccess.body.vehicles : NO_VEHICLES;
}

export function liveMotionDurationOf(resource: PolledResource<RouteWatch>): number {
  if (resource.status !== "success" || resource.lifetime.noStore || resource.lifetime.maxAgeSeconds === null) {
    return DEFAULT_LIVE_MOTION_DURATION_MS;
  }

  const remainingFreshSeconds = Math.max(0, resource.lifetime.maxAgeSeconds - resource.lifetime.ageSeconds);
  return Math.max(MIN_LIVE_MOTION_DURATION_MS, remainingFreshSeconds * 1_000);
}
