import type { Event } from "@sentry/react";
import { redactedValueFrom, safeUrlFrom, requestPathFrom, redactedTextFrom } from "./privacy";

export interface PageErrorContext {
  path: string;
  tags?: Record<string, string>;
}

export function eventWithContextFor<T extends Event>(original: T, page: PageErrorContext, userAgent: string): T {
  const event = redactedValueFrom(original);
  const safePage = redactedValueFrom(page);
  const safeUserAgent = redactedTextFrom(userAgent);
  delete event.user;
  if (event.request) {
    const url = event.request.url;
    event.request = {
      ...(url ? { url: safeUrlFrom(url) } : {}),
      headers: { "User-Agent": safeUserAgent },
    };
  }
  event.extra = { ...event.extra, page_path: requestPathFrom(safePage.path), browser_user_agent: safeUserAgent };
  event.tags = { ...safePage.tags, ...event.tags };
  const routeId = event.tags.route_id;
  if (routeId !== undefined) {
    event.tags.route_id = typeof routeId === "string" && /^[0-9]{9}$/.test(routeId) ? routeId : "invalid";
  }
  return event;
}

export const EVENT_LIMIT_BYTES = 1024 * 1024;
/** 응답 본문 기록의 임시 상한. 추후 Sentry에서 수집한 본문 크기와 잘림 여부를 바탕으로 조정한다. */
export const RESPONSE_BODY_LIMIT_BYTES = 32 * 1024;
const encoder = new TextEncoder();

export function utf8ByteLengthFrom(value: string): number {
  return encoder.encode(value).byteLength;
}

export function responseBodyWithinLimitFor(body: string) {
  const { read, written } = encoder.encodeInto(body, new Uint8Array(RESPONSE_BODY_LIMIT_BYTES));
  return { body: body.slice(0, read), bytes: written, truncated: read < body.length };
}

export function createRateLimiter(intervalMs = 60_000, storage?: Pick<Storage, "getItem" | "setItem">) {
  const sentAt = new Map<string, number>();
  const storageKey = "salmonbus.sentry.sentAt";
  try {
    const saved: unknown = JSON.parse(storage?.getItem(storageKey) ?? "[]");
    if (Array.isArray(saved)) {
      for (const entry of saved) {
        if (
          Array.isArray(entry) &&
          typeof entry[0] === "string" &&
          typeof entry[1] === "number" &&
          Number.isFinite(entry[1])
        ) {
          sentAt.set(entry[0], entry[1]);
        }
      }
    }
  } catch {
    // 저장소 기록을 복원하지 못해도 메모리로 중복 기록을 제한합니다.
  }
  return (key: string, now: number): boolean => {
    for (const [existing, timestamp] of sentAt) {
      if (now - timestamp >= intervalMs || timestamp > now) sentAt.delete(existing);
    }
    if (sentAt.has(key)) return false;
    sentAt.set(key, now);
    try {
      storage?.setItem(storageKey, JSON.stringify([...sentAt]));
    } catch {
      // 저장소 쓰기에 실패해도 메모리 기록을 유지하고 오류 보고를 계속합니다.
    }
    return true;
  };
}

export function eventKeyFor(event: Event): string {
  if (event.tags?.source === "api") {
    return JSON.stringify([event.extra?.api_path, event.fingerprint]);
  }
  return JSON.stringify([
    event.tags?.react_error_kind,
    event.message,
    event.exception?.values?.map(({ type, value, stacktrace }) => [type, value, stacktrace?.frames]),
  ]);
}

export function eventWithinLimitFor<T extends Event>(original: T, limitBytes = EVENT_LIMIT_BYTES) {
  const event = { ...original, extra: { ...original.extra } };
  const body = event.extra.response_body;
  if (typeof body === "string") event.extra.response_body_sent_bytes = utf8ByteLengthFrom(body);
  return utf8ByteLengthFrom(JSON.stringify(event)) < limitBytes ? event : null;
}
