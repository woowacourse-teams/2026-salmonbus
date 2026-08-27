const WHOLE_SECONDS = /^\d+$/;

export function retryAfterMillisFrom(headers: Headers, now: number): number | null {
  const retryAfter = headers.get("retry-after");
  if (retryAfter === null) {
    return null;
  }

  if (WHOLE_SECONDS.test(retryAfter)) {
    return Number(retryAfter) * 1000;
  }

  const retryAt = Date.parse(retryAfter);
  if (Number.isNaN(retryAt)) {
    return null;
  }

  return Math.max(0, retryAt - now);
}
