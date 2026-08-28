import { cacheLifetimeFrom, type CacheLifetime } from "./cacheControl";
import { referenceClockFrom, type ReferenceClock } from "./referenceClock";
import { retryAfterMillisFrom } from "./retryAfter";
import type { ErrorCode, ErrorResponse } from "./routeForecast.types";

const REQUEST_TIMEOUT_MS = 10_000;
const TIMEOUT_REASON = "timeout";

const ERROR_CODES: ReadonlySet<string> = new Set<ErrorCode>([
  "INVALID_ROUTE_ID",
  "ROUTE_NOT_FOUND",
  "MODEL_OUT_OF_SCOPE",
  "NO_RECENT_OBSERVATION",
  "SERVICE_UNAVAILABLE",
]);

export interface ApiSuccess<T> {
  ok: true;
  body: T;
  status: number;
  requestId: string | null;
  clock: ReferenceClock | null;
  lifetime: CacheLifetime;
}

export type ApiFailure =
  | { kind: "contract"; error: ErrorResponse; status: number; retryAfterMs: number | null }
  | { kind: "rateLimited"; retryAfterMs: number | null; requestId: string | null }
  | { kind: "methodNotAllowed"; requestId: string | null }
  | { kind: "malformed"; status: number; requestId: string | null }
  | { kind: "timeout" }
  | { kind: "aborted" }
  | { kind: "network"; cause: unknown };

export interface ApiError {
  ok: false;
  failure: ApiFailure;
}

export type ApiResult<T> = ApiSuccess<T> | ApiError;

export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

export async function requestJson<T>(url: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  if (options.signal?.aborted) {
    return failed({ kind: "aborted" });
  }

  const controller = new AbortController();
  const forwardAbort = () => controller.abort(options.signal?.reason);
  options.signal?.addEventListener("abort", forwardAbort, { once: true });
  const timeout = setTimeout(() => controller.abort(TIMEOUT_REASON), options.timeoutMs ?? REQUEST_TIMEOUT_MS);

  let response: Response | null = null;
  try {
    response = await fetch(url, { headers: { Accept: "application/json" }, signal: controller.signal });
    const clock = referenceClockFrom(response.headers);
    const infrastructureFailure = infrastructureFailureOf(response, clock);
    if (infrastructureFailure !== null) {
      return failed(infrastructureFailure);
    }

    const body: unknown = await response.json();
    return interpret<T>(response, body, clock);
  } catch (cause) {
    if (controller.signal.aborted) {
      return failed(controller.signal.reason === TIMEOUT_REASON ? { kind: "timeout" } : { kind: "aborted" });
    }
    if (response !== null) {
      return failed({ kind: "malformed", status: response.status, requestId: requestIdOf(response) });
    }
    return failed({ kind: "network", cause });
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", forwardAbort);
  }
}

function infrastructureFailureOf(response: Response, clock: ReferenceClock | null): ApiFailure | null {
  if (response.status === 429) {
    return { kind: "rateLimited", retryAfterMs: retryAfterMsOf(response, clock), requestId: requestIdOf(response) };
  }
  if (response.status === 405) {
    return { kind: "methodNotAllowed", requestId: requestIdOf(response) };
  }
  return null;
}

function interpret<T>(response: Response, body: unknown, clock: ReferenceClock | null): ApiResult<T> {
  const requestId = requestIdOf(response);

  if (response.ok) {
    return {
      ok: true,
      body: body as T,
      status: response.status,
      requestId,
      clock,
      lifetime: cacheLifetimeFrom(response.headers),
    };
  }

  if (isErrorResponse(body)) {
    return failed({
      kind: "contract",
      error: body,
      status: response.status,
      retryAfterMs: retryAfterMsOf(response, clock),
    });
  }

  return failed({ kind: "malformed", status: response.status, requestId });
}

function isErrorResponse(value: unknown): value is ErrorResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.code === "string" &&
    ERROR_CODES.has(candidate.code) &&
    typeof candidate.message === "string" &&
    typeof candidate.requestId === "string" &&
    typeof candidate.retryable === "boolean"
  );
}

function retryAfterMsOf(response: Response, clock: ReferenceClock | null): number | null {
  return clock === null ? null : retryAfterMillisFrom(response.headers, clock.now());
}

function requestIdOf(response: Response): string | null {
  return response.headers.get("x-request-id");
}

function failed(failure: ApiFailure): ApiError {
  return { ok: false, failure };
}
