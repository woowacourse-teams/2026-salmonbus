import { isPositiveInteger, isRecord } from "./valueGuards";

export interface ChatMessage {
  id: string;
  authorId: string;
  nickname: string;
  body: string;
  createdAt: string;
}

export interface ChatErrorFrame {
  v: 1;
  type: "error";
  requestId: string | null;
  code: string;
  message: string;
  retryAfterMs: number | null;
  fatal: boolean;
}

export type ServerFrame =
  | {
      v: 1;
      type: "session.ready";
      authorId: string;
      nickname: string;
      maxBodyCodePoints: number;
    }
  | { v: 1; type: "history.batch"; messages: ChatMessage[] }
  | { v: 1; type: "history.end" }
  | { v: 1; type: "message.ack"; clientMessageId: string; duplicate: boolean; message: ChatMessage }
  | { v: 1; type: "message.created"; message: ChatMessage }
  | ChatErrorFrame;

export type ChatErrorCode =
  | "INVALID_FRAME"
  | "HELLO_REQUIRED"
  | "UNSUPPORTED_PROTOCOL"
  | "INVALID_BODY"
  | "RATE_LIMITED"
  | "ID_CONFLICT"
  | "CHAT_UNAVAILABLE";

const ERROR_CODES: ReadonlySet<string> = new Set<ChatErrorCode>([
  "INVALID_FRAME",
  "HELLO_REQUIRED",
  "UNSUPPORTED_PROTOCOL",
  "INVALID_BODY",
  "RATE_LIMITED",
  "ID_CONFLICT",
  "CHAT_UNAVAILABLE",
]);

export function isKnownErrorCode(code: string): code is ChatErrorCode {
  return ERROR_CODES.has(code);
}

export function parseServerFrame(raw: string): ServerFrame | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }

  if (!isRecord(value) || value.v !== 1 || typeof value.type !== "string") return null;

  switch (value.type) {
    case "session.ready":
      if (
        typeof value.authorId !== "string" ||
        typeof value.nickname !== "string" ||
        !isPositiveInteger(value.maxBodyCodePoints)
      ) {
        return null;
      }
      return {
        v: 1,
        type: "session.ready",
        authorId: value.authorId,
        nickname: value.nickname,
        maxBodyCodePoints: value.maxBodyCodePoints,
      };
    case "history.batch": {
      if (!Array.isArray(value.messages)) return null;
      const messages = value.messages.map(messageFrom);
      if (messages.some((message) => message === null)) return null;
      return { v: 1, type: "history.batch", messages: messages as ChatMessage[] };
    }
    case "history.end":
      return { v: 1, type: "history.end" };
    case "message.ack": {
      const message = messageFrom(value.message);
      if (typeof value.clientMessageId !== "string" || typeof value.duplicate !== "boolean" || message === null) {
        return null;
      }
      return {
        v: 1,
        type: "message.ack",
        clientMessageId: value.clientMessageId,
        duplicate: value.duplicate,
        message,
      };
    }
    case "message.created": {
      const message = messageFrom(value.message);
      return message === null ? null : { v: 1, type: "message.created", message };
    }
    case "error":
      return errorFrameFrom(value);
    default:
      return null;
  }
}

function errorFrameFrom(value: Record<string, unknown>): ChatErrorFrame | null {
  const requestId = value.requestId ?? null;
  const retryAfterMs = value.retryAfterMs ?? null;
  if (
    typeof value.code !== "string" ||
    typeof value.message !== "string" ||
    typeof value.fatal !== "boolean" ||
    (requestId !== null && typeof requestId !== "string") ||
    (retryAfterMs !== null && !isNonNegativeInteger(retryAfterMs))
  ) {
    return null;
  }
  return {
    v: 1,
    type: "error",
    requestId,
    code: value.code,
    message: value.message,
    retryAfterMs,
    fatal: value.fatal,
  };
}

function messageFrom(value: unknown): ChatMessage | null {
  if (!isRecord(value)) return null;
  if (
    typeof value.id !== "string" ||
    typeof value.authorId !== "string" ||
    typeof value.nickname !== "string" ||
    typeof value.body !== "string" ||
    typeof value.createdAt !== "string" ||
    !Number.isFinite(Date.parse(value.createdAt))
  ) {
    return null;
  }
  return {
    id: value.id,
    authorId: value.authorId,
    nickname: value.nickname,
    body: value.body,
    createdAt: value.createdAt,
  };
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}
