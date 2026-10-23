import { isRecord } from "@/shared/utils/valueGuards";
import { cacheLifetimeFrom, type CacheLifetime } from "./cacheControl";
import { referenceClockFrom, type ReferenceClock } from "./referenceClock";
import { retryAfterMillisFrom } from "./retryAfter";
import { ERROR_CODES, type ErrorResponse } from "./apiError";

const REQUEST_TIMEOUT_MS = 10_000;
const TIMEOUT_REASON = "timeout";

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
  | { kind: "rateLimited"; status: number; retryAfterMs: number | null; requestId: string | null }
  | { kind: "malformed"; status: number; requestId: string | null }
  | { kind: "timeout" }
  | { kind: "aborted" }
  | { kind: "network"; cause: unknown };

export interface ApiError {
  ok: false;
  failure: ApiFailure;
}

export type ApiResult<T> = ApiSuccess<T> | ApiError;

export interface RequestErrorContext {
  endpoint: string;
  tags: Record<string, string>;
}

export interface ApiFailureDetails {
  context: RequestErrorContext | null;
  abortReason: unknown;
  rawResponse: { body: string; contentType: string | null } | null;
}

export type ApiFailureReporter = (url: string, failure: ApiFailure, details: ApiFailureDetails) => void;

let failureReporter: ApiFailureReporter | null = null;

export function initApiFailureReporter(reporter: ApiFailureReporter): void {
  failureReporter = reporter;
}

export function resetApiFailureReporter(): void {
  failureReporter = null;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  errorContext?: RequestErrorContext;
}

interface RequestOutcome<T> {
  result: ApiResult<T>;
  details: ApiFailureDetails;
}

export async function requestJson<T>(url: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  const { result, details } = await executeRequest<T>(url, options);
  if (!result.ok && failureReporter) {
    try {
      failureReporter(url, result.failure, details);
    } catch {
      // 수집 실패가 원래 요청 결과나 재시도 동작을 바꾸면 안 된다.
    }
  }
  return result;
}

async function executeRequest<T>(url: string, options: RequestOptions): Promise<RequestOutcome<T>> {
  let responseBody: string | undefined;
  let contentType: string | null = null;
  const outcomeOf = (result: ApiResult<T>): RequestOutcome<T> => ({
    result,
    details: {
      context: options.errorContext ?? null,
      abortReason: options.signal?.reason ?? null,
      rawResponse:
        !result.ok &&
        result.failure.kind === "malformed" &&
        responseBody !== undefined &&
        !/^\/api\/(auth|me)(\/|$)/.test(url)
          ? { body: responseBody, contentType }
          : null,
    },
  });
  if (options.signal?.aborted) return outcomeOf(failed({ kind: "aborted" }));

  const controller = new AbortController();
  const forwardAbort = () => controller.abort(options.signal?.reason);
  options.signal?.addEventListener("abort", forwardAbort, { once: true });
  const timeout = setTimeout(() => controller.abort(TIMEOUT_REASON), REQUEST_TIMEOUT_MS);

  let response: Response | null = null;
  try {
    response = await fetch(url, {
      method: options.method ?? "GET",
      headers: {
        Accept: "application/json",
        ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
        ...options.headers,
      },
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
      signal: controller.signal,
    });
    contentType = response.headers.get("content-type");
    const clock = referenceClockFrom(response.headers);
    const infrastructureFailure = infrastructureFailureOf(response, clock);
    if (infrastructureFailure !== null) {
      return outcomeOf(failed(infrastructureFailure));
    }

    if (response.status === 204) return outcomeOf(interpret<T>(response, undefined, clock));
    responseBody = await response.text();
    const body: unknown = JSON.parse(responseBody);
    return outcomeOf(interpret<T>(response, body, clock));
  } catch (cause) {
    if (controller.signal.aborted) {
      return outcomeOf(failed(controller.signal.reason === TIMEOUT_REASON ? { kind: "timeout" } : { kind: "aborted" }));
    }
    if (response !== null) {
      return outcomeOf(failed({ kind: "malformed", status: response.status, requestId: requestIdOf(response) }));
    }
    return outcomeOf(failed({ kind: "network", cause }));
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", forwardAbort);
  }
}

function infrastructureFailureOf(response: Response, clock: ReferenceClock | null): ApiFailure | null {
  if (response.status === 429) {
    return {
      kind: "rateLimited",
      status: response.status,
      retryAfterMs: retryAfterMsOf(response, clock),
      requestId: requestIdOf(response),
    };
  }
  return null;
}

function interpret<T>(response: Response, body: unknown, clock: ReferenceClock | null): ApiResult<T> {
  const requestId = requestIdOf(response);

  if (response.ok) {
    return {
      ok: true,
      // shortcut: 기존 REST 성공 본문은 미검증이며 SAL-140에서 파서 경계로 교체한다.
      body: body as T,
      status: response.status,
      requestId,
      clock,
      lifetime: cacheLifetimeFrom(response.headers),
    };
  }

  const error = errorResponseFrom(body);
  if (error !== null) {
    return failed({
      kind: "contract",
      error,
      status: response.status,
      retryAfterMs: retryAfterMsOf(response, clock),
    });
  }

  return failed({ kind: "malformed", status: response.status, requestId });
}

function errorResponseFrom(value: unknown): ErrorResponse | null {
  if (!isRecord(value) || typeof value.message !== "string" || typeof value.requestId !== "string") return null;
  const code = ERROR_CODES.find((code) => code === value.code);
  if (code === undefined) return null;
  return { code, message: value.message, requestId: value.requestId };
}

function retryAfterMsOf(response: Response, clock: ReferenceClock | null): number | null {
  return retryAfterMillisFrom(response.headers, clock === null ? null : clock.now());
}

function requestIdOf(response: Response): string | null {
  return response.headers.get("x-request-id");
}

function failed(failure: ApiFailure): ApiError {
  return { ok: false, failure };
}
