import { cacheLifetimeFrom } from "./cacheControl";

export interface ReferenceClock {
  readonly servedAt: number;
  now(): number;
}

export function referenceClockFrom(
  headers: Headers,
  elapsedNow: () => number = () => performance.now(),
): ReferenceClock | null {
  const date = headers.get("date");
  if (date === null) return null;

  const originAt = Date.parse(date);
  if (Number.isNaN(originAt)) return null;

  const servedAt = originAt + cacheLifetimeFrom(headers).ageSeconds * 1000;
  const receivedAt = elapsedNow();

  return { servedAt, now: () => servedAt + (elapsedNow() - receivedAt) };
}
