import * as Sentry from "@sentry/react";
import type { RootOptions } from "react-dom/client";
import type { ApiFailureReporter } from "@/shared/api/client";
import { cancellationReasonFrom } from "@/shared/api/cancellation";
import {
  utf8ByteLengthFrom,
  createRateLimiter,
  eventKeyFor,
  eventWithinLimitFor,
  eventWithContextFor,
  type PageErrorContext,
} from "./eventPolicy";
import { redactedBodyFrom, redactedValueFrom, requestPathFrom } from "./privacy";

interface ErrorTrackingConfiguration {
  dsn: string;
  environment: string;
  release: string;
  storage?: Storage;
  contextForPage: () => PageErrorContext;
}

const createErrorTransport: NonNullable<Sentry.BrowserOptions["transport"]> = (options) => {
  const nativeFetch = globalThis.fetch.bind(globalThis);
  let pendingBytes = 0;
  let pendingCount = 0;
  const request: typeof fetch = async (input, init) => {
    const body = init?.body;
    const size = typeof body === "string" ? utf8ByteLengthFrom(body) : body instanceof Uint8Array ? body.byteLength : 0;
    pendingBytes += size;
    pendingCount += 1;
    try {
      // keepalive 한도는 글자 수가 아닌 바이트다. 큰 한글 본문은 일반 fetch로 보낸다.
      return await nativeFetch(input, { ...init, keepalive: pendingBytes <= 60_000 && pendingCount < 15 });
    } finally {
      pendingBytes -= size;
      pendingCount -= 1;
    }
  };
  const transport = Sentry.makeFetchTransport({ ...options, fetchOptions: { credentials: "omit" } }, request);
  return {
    flush: (timeout) => transport.flush(timeout),
    send(envelope) {
      const items: [{ type: "event" }, Sentry.Event][] = [];
      for (const [headers, payload] of envelope[1]) {
        if (headers.type !== "event") continue;
        const event = eventWithinLimitFor(payload as Sentry.Event);
        if (event !== null) items.push([{ type: "event" }, event]);
        else options.recordDroppedEvent("event_processor", "error");
      }
      return items.length === 0
        ? Promise.resolve({})
        : transport.send([envelope[0], items] as Parameters<typeof transport.send>[0]);
    },
  };
};

export function initErrorTracking(configuration: ErrorTrackingConfiguration) {
  const allowEvent = createRateLimiter(60_000, configuration.storage);
  Sentry.init({
    dsn: configuration.dsn,
    environment: configuration.environment,
    release: configuration.release,
    sampleRate: 1,
    tracesSampleRate: 0,
    sendClientReports: false,
    transport: createErrorTransport,
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpHeaders: { allow: ["User-Agent"] },
      httpBodies: [],
      urlQueryParams: false,
      stackFrameVariables: false,
    },
    integrations: (defaults) => [
      ...defaults.filter(
        ({ name }) => !["BrowserSession", "Dedupe", "Breadcrumbs", "Console", "ConversationId"].includes(name),
      ),
      Sentry.breadcrumbsIntegration({ dom: false, history: true, fetch: true, xhr: true, sentry: false }),
    ],
    beforeBreadcrumb(breadcrumb) {
      if (!["fetch", "xhr", "navigation", "request.cancelled"].includes(breadcrumb.category ?? "")) return null;
      return redactedValueFrom(breadcrumb);
    },
    beforeSend(original) {
      const event = eventWithContextFor(original, configuration.contextForPage(), navigator.userAgent);
      return allowEvent(eventKeyFor(event), performance.timeOrigin + performance.now()) ? event : null;
    },
  });
  return {
    apiFailureReporter: createApiFailureReporter(
      (error, event) =>
        Sentry.captureException(error, {
          level: "error",
          tags: event.tags ?? {},
          extra: event.extra ?? {},
          fingerprint: event.fingerprint ?? [],
        }),
      Sentry.addBreadcrumb,
    ),
    reactErrorHandlers: createReactErrorHandlers(Sentry.captureReactException),
  };
}

export function createReactErrorHandlers(requestCapture: typeof Sentry.captureReactException): RootOptions {
  const createHandler =
    (kind: "caught" | "uncaught" | "recoverable") =>
    (error: unknown, info: { componentStack?: string | undefined }) => {
      // SDK 일반 헬퍼의 callback 유무가 아니라 실제 React 처리 상태를 기록한다.
      requestCapture(
        error,
        { componentStack: info.componentStack ?? null },
        {
          mechanism: { type: "auto.function.react.error_handler", handled: kind !== "uncaught" },
          captureContext: { tags: { react_error_kind: kind } },
        },
      );
    };
  return {
    onCaughtError: createHandler("caught"),
    onUncaughtError: createHandler("uncaught"),
    onRecoverableError: createHandler("recoverable"),
  };
}

export function createApiFailureReporter(
  requestCapture: (error: Error, event: Sentry.Event) => unknown,
  requestBreadcrumb: (breadcrumb: Sentry.Breadcrumb) => unknown,
): ApiFailureReporter {
  return (url, failure, details) => {
    const path = requestPathFrom(url);
    const reason = cancellationReasonFrom(details.abortReason);
    if (failure.kind === "aborted" && reason !== "unknown") {
      requestBreadcrumb({ category: "request.cancelled", level: "info", data: { api_path: path, reason } });
      return;
    }
    const status = "status" in failure ? failure.status : null;
    const code = failure.kind === "contract" ? failure.error.code : failure.kind;
    const requestId =
      failure.kind === "contract" ? failure.error.requestId : "requestId" in failure ? failure.requestId : null;
    // 요청을 소유한 쪽에서 노선 정보와 그룹 경로를 전달한다. 수집기는 URL의 업무 의미를 해석하지 않는다.
    const endpoint = details.context?.endpoint ?? path;
    const event: Sentry.Event = {
      level: "error",
      fingerprint: ["api", endpoint, failure.kind, String(status), code],
      tags: { ...details.context?.tags, source: "api", failure_kind: failure.kind, error_code: code },
      extra: {
        api_path: path,
        http_method: "GET",
        http_status: status,
        request_id: requestId,
        ...(failure.kind === "contract" ? { error_message: failure.error.message } : {}),
        ...(failure.kind === "aborted" ? { cancellation_reason: reason } : {}),
      },
    };
    if (failure.kind === "malformed" && details.rawResponse !== null) {
      const body = redactedBodyFrom(details.rawResponse.body);
      event.extra = {
        ...event.extra,
        response_body: body,
        response_body_original_bytes: utf8ByteLengthFrom(details.rawResponse.body),
        response_body_redacted_bytes: utf8ByteLengthFrom(body),
        response_content_type: details.rawResponse.contentType,
      };
    }
    const error = new Error("API " + failure.kind + ": " + endpoint + (status === null ? "" : " (" + status + ")"));
    error.name = "ApiRequestFailure";
    requestCapture(error, event);
  };
}
