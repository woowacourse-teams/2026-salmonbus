import type { ChatMessage } from "./chatProtocol";

export type Delivery = "sending" | "failed";

export interface PendingMessage {
  clientMessageId: string;
  body: string;
  createdAt: string;
  delivery: Delivery;
  failureMessage?: string;
  retryWithNewId?: boolean;
}

export interface ChatDataState {
  messages: ChatMessage[];
  pending: PendingMessage[];
}

export type ChatDataAction =
  | { type: "history.received"; messages: ChatMessage[] }
  | { type: "message.created"; message: ChatMessage }
  | { type: "message.pending"; message: PendingMessage }
  | { type: "message.ack"; clientMessageId: string; message: ChatMessage }
  | { type: "message.failed"; clientMessageId: string; failureMessage: string; retryWithNewId?: boolean }
  | { type: "message.retry"; clientMessageId: string }
  | { type: "message.rekey"; clientMessageId: string; nextClientMessageId: string }
  | { type: "connection.lost"; failureMessage: string };

export const initialChatDataState: ChatDataState = { messages: [], pending: [] };

export function chatDataReducer(state: ChatDataState, action: ChatDataAction): ChatDataState {
  switch (action.type) {
    case "history.received":
      return { ...state, messages: mergeMessages(state.messages, action.messages) };
    case "message.created":
      return { ...state, messages: mergeMessages(state.messages, [action.message]) };
    case "message.pending":
      return { ...state, pending: [...state.pending, action.message] };
    case "message.ack":
      return {
        messages: mergeMessages(state.messages, [action.message]),
        pending: state.pending.filter((message) => message.clientMessageId !== action.clientMessageId),
      };
    case "message.failed":
      return {
        ...state,
        pending: state.pending.map((message) =>
          message.clientMessageId === action.clientMessageId
            ? {
                ...message,
                delivery: "failed",
                failureMessage: action.failureMessage,
                ...(action.retryWithNewId === true ? { retryWithNewId: true } : {}),
              }
            : message,
        ),
      };
    case "message.retry":
      return {
        ...state,
        pending: state.pending.map((message) =>
          message.clientMessageId === action.clientMessageId
            ? {
                clientMessageId: message.clientMessageId,
                body: message.body,
                createdAt: message.createdAt,
                delivery: "sending",
              }
            : message,
        ),
      };
    case "message.rekey":
      return {
        ...state,
        pending: state.pending.map((message) =>
          message.clientMessageId === action.clientMessageId
            ? {
                clientMessageId: action.nextClientMessageId,
                body: message.body,
                createdAt: message.createdAt,
                delivery: "sending",
              }
            : message,
        ),
      };
    case "connection.lost":
      return {
        ...state,
        pending: state.pending.map((message) =>
          message.delivery === "sending"
            ? { ...message, delivery: "failed", failureMessage: action.failureMessage }
            : message,
        ),
      };
  }
}

export function mergeMessages(current: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((left, right) => {
    const timeDifference = Date.parse(left.createdAt) - Date.parse(right.createdAt);
    return timeDifference === 0 ? left.id.localeCompare(right.id) : timeDifference;
  });
}
