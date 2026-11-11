import { latestSuccessOf, type PolledResource } from "./polledResource";
import type { ArrivalEta, StationArrivalsResponse } from "./stationArrivals.types";

export function freshStationArrivalsOf(resource: PolledResource<StationArrivalsResponse>, stationId: string) {
  const latest = latestSuccessOf(resource);
  if (!latest?.clock || latest.body.stationId !== stationId) return null;
  const { body, clock } = latest;
  const now = clock.now();
  if (
    body.status !== "READY" ||
    body.reason === "STALE" ||
    !body.snapshot ||
    !(now < Date.parse(body.snapshot.staleAt))
  ) {
    return null;
  }
  return { body, now };
}

export function remainingArrivalSeconds(
  eta: Pick<ArrivalEta, "status" | "remainingSeconds"> & { expectedArrivalAt: string | null },
  now: number,
): number | null {
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null || eta.expectedArrivalAt === null) return null;
  const seconds = (Date.parse(eta.expectedArrivalAt) - now) / 1_000;
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null;
}
