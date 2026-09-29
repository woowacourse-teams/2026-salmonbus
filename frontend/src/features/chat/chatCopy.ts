import { isKnownErrorCode, type ChatErrorCode, type ChatErrorFrame } from "./chatProtocol";
import type { ConnectionState } from "./connectionPolicy";
import type { Delivery } from "./messagePolicy";
import type { SheetPosition } from "./sheetPolicy";

export type StatusTone = "progress" | "success" | "error";

export interface StatusNotice {
  tone: StatusTone;
  text: string;
}

export const RECONNECTED_NOTICE: StatusNotice = { tone: "success", text: "다시 연결됐어요" };
export const RATE_LIMITED_TEXT = "메시지를 너무 빨리 보냈어요. 잠시 후 다시 보내 주세요.";
export const SEND_FAILED_TEXT = "보내지 못했어요.";
export const ACK_TIMEOUT_TEXT = "응답이 늦어 보내지 못했어요.";
export const CONNECTION_LOST_TEXT = "연결이 끊겨 보내지 못했어요.";

export const CHAT_NAME = "채팅";
export const COLLAPSE_LABEL = "채팅 접기";
export const RECONNECT_LABEL = "다시 연결";
export const RETRY_SEND_LABEL = "다시 보내기";
export const MESSAGE_INPUT_LABEL = "메시지";
export const SEND_LABEL = "메시지 보내기";
export const HISTORY_LOADING_LABEL = "최근 대화를 불러오는 중";
export const EMPTY_ROOM_TITLE = "아직 대화가 없어요";
export const EMPTY_ROOM_HINT = "같은 버스를 기다리는 사람에게 먼저 말을 걸어 보세요.";

const CONNECTION_NOTICES: Record<Exclude<ConnectionState, "ready">, StatusNotice> = {
  connecting: { tone: "progress", text: "대화방에 연결하는 중이에요" },
  reconnecting: { tone: "progress", text: "연결이 끊겼어요. 다시 연결하는 중이에요" },
  failed: { tone: "error", text: "대화방에 연결하지 못했어요" },
  unavailable: { tone: "error", text: "지금은 채팅을 쓸 수 없어요" },
};

const ERROR_TEXTS: Record<ChatErrorCode, string> = {
  RATE_LIMITED: RATE_LIMITED_TEXT,
  INVALID_BODY: "메시지 내용을 확인해 주세요.",
  ID_CONFLICT: "메시지를 다시 작성해 보내 주세요.",
  CHAT_UNAVAILABLE: "지금은 채팅을 쓸 수 없어요.",
  INVALID_FRAME: "대화방에 연결하지 못했어요",
  HELLO_REQUIRED: "대화방에 연결하지 못했어요",
  UNSUPPORTED_PROTOCOL: "대화방에 연결하지 못했어요",
};

const DELIVERY_LABELS: Record<Delivery, string> = {
  sending: "보내는 중",
  failed: "전송 실패",
};

export function connectionNotice(connection: ConnectionState): StatusNotice | null {
  return connection === "ready" ? null : CONNECTION_NOTICES[connection];
}

export function errorText(error: ChatErrorFrame): string {
  return isKnownErrorCode(error.code) ? ERROR_TEXTS[error.code] : error.message;
}

export function composerPlaceholder(connection: ConnectionState): string {
  if (connection === "ready") return "버스 상황을 나눠 보세요";
  if (connection === "failed") return "다시 연결하면 보낼 수 있어요";
  if (connection === "unavailable") return "지금은 메시지를 보낼 수 없어요";
  return "연결되면 보낼 수 있어요";
}

export function launcherLabel(displayName: string, unread: number): string {
  return `${displayName} 채팅 열기${unread > 0 ? `, 안 읽은 메시지 ${unread}개` : ""}`;
}

export function sheetLabel(displayName: string): string {
  return `${displayName} 채팅`;
}

export function messageLogLabel(displayName: string): string {
  return `${displayName} 채팅 메시지`;
}

export function sizeToggleLabel(position: SheetPosition): string {
  return position === "full" ? "채팅 절반으로 보기" : "채팅 크게 보기";
}

export function nicknameLabel(nickname: string | null): string {
  return nickname === null ? "익명 이름을 준비하고 있어요" : `내 이름 ${nickname}`;
}

export function deliveryLabel(delivery: Delivery): string {
  return DELIVERY_LABELS[delivery];
}

export function newMessagesLabel(count: number): string {
  return `새 메시지 ${count}개`;
}

export function messageTimeLabel(createdAt: string): string {
  return new Intl.DateTimeFormat("ko-KR", { hour: "numeric", minute: "2-digit" }).format(new Date(createdAt));
}
