const MAX_AGE_DIRECTIVE = /^max-age="?(\d+)"?$/i;
const WHOLE_SECONDS = /^\d+$/;

export interface CacheLifetime {
  noStore: boolean;
  maxAgeSeconds: number | null;
  ageSeconds: number;
}

export function cacheLifetimeFrom(headers: Headers): CacheLifetime {
  const directives = (headers.get("cache-control") ?? "").split(",").map((directive) => directive.trim());
  const age = headers.get("age");

  return {
    noStore: directives.some((directive) => directive.toLowerCase() === "no-store"),
    maxAgeSeconds: maxAgeSecondsIn(directives),
    ageSeconds: age !== null && WHOLE_SECONDS.test(age) ? Number(age) : 0,
  };
}

function maxAgeSecondsIn(directives: string[]): number | null {
  for (const directive of directives) {
    const seconds = MAX_AGE_DIRECTIVE.exec(directive)?.[1];
    if (seconds !== undefined) {
      return Number(seconds);
    }
  }

  return null;
}

export function remainingFreshSecondsFrom(headers: Headers): number | null {
  const { noStore, maxAgeSeconds, ageSeconds } = cacheLifetimeFrom(headers);
  if (noStore || maxAgeSeconds === null) {
    return null;
  }

  return Math.max(0, maxAgeSeconds - ageSeconds);
}
