import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import {
  ACK_TIMEOUT_TEXT,
  CONNECTION_LOST_TEXT,
  RECONNECTED_NOTICE,
  SEND_FAILED_TEXT,
  errorText,
  type StatusNotice,
} from "./chatCopy";
import {
  isKnownErrorCode,
  parseServerFrame,
  type ChatErrorFrame,
  type ChatMessage,
  type ServerFrame,
} from "./chatProtocol";
import { createClientId } from "./clientIdPolicy";
import { reconnectDelayMs, shouldReconnect, type ConnectionState } from "./connectionPolicy";
import { chatDataReducer, initialChatDataState, type PendingMessage } from "./messagePolicy";

interface ChatConnectionOptions {
  routeId: string;
  initialMaxBodyCodePoints: number;
  started: boolean;
  onIncoming(): void;
}

interface FrameHandlers {
  onReady(authorId: string, nickname: string, maxBodyCodePoints: number): void;
  onHistory(messages: ChatMessage[]): void;
  onHistoryEnd(): void;
  onAck(clientMessageId: string, message: ChatMessage): void;
  onCreated(message: ChatMessage): void;
  onError(frame: ChatErrorFrame): void;
}

type AckTimers = Map<string, number>;

const SESSION_STORAGE_KEY = "salmonbus.chat.client-session-id.v1";
const ACK_TIMEOUT_MS = 10_000;
const RECONNECTED_NOTICE_MS = 2_000;
const DEFAULT_RETRY_AFTER_MS = 1_000;

export function useChatConnection({ routeId, initialMaxBodyCodePoints, started, onIncoming }: ChatConnectionOptions) {
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  const [historyComplete, setHistoryComplete] = useState(false);
  const [authorId, setAuthorId] = useState<string | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const [maxBodyCodePoints, setMaxBodyCodePoints] = useState(initialMaxBodyCodePoints);
  const [notice, setNotice] = useState<StatusNotice | null>(null);
  const [rateLimited, setRateLimited] = useState(false);
  const [reconnectKey, setReconnectKey] = useState(0);
  const [data, dispatch] = useReducer(chatDataReducer, initialChatDataState);

  const [sessionId] = useState(getOrCreateSessionId);
  const socketRef = useRef<WebSocket | null>(null);
  const authorIdRef = useRef<string | null>(null);
  const ackTimersRef = useRef<AckTimers>(new Map());

  const markPendingFailed = useCallback((clientMessageId: string, failureMessage: string, retryWithNewId = false) => {
    clearAckTimer(ackTimersRef.current, clientMessageId);
    dispatch({
      type: "message.failed",
      clientMessageId,
      failureMessage,
      ...(retryWithNewId ? { retryWithNewId: true } : {}),
    });
  }, []);

  useEffect(() => {
    if (!started) return;

    const ackTimers = ackTimersRef.current;
    let disposed = false;
    let retryTimer: number | null = null;
    let attempt = 0;
    let hasBeenReady = false;
    let fatal = false;

    const openSocket = () => {
      if (disposed) return;
      setConnection(hasBeenReady || attempt > 0 ? "reconnecting" : "connecting");
      setHistoryComplete(false);

      const socket = new WebSocket(chatWebSocketUrl(routeId));
      socketRef.current = socket;

      socket.addEventListener("open", () => {
        if (disposed) return;
        socket.send(JSON.stringify({ v: 1, type: "session.start", clientSessionId: sessionId }));
      });

      socket.addEventListener("message", (event) => {
        if (disposed || typeof event.data !== "string") return;
        const frame = parseServerFrame(event.data);
        if (frame === null) return;

        handleFrame(frame, {
          onReady: (nextAuthorId, nextNickname, nextMaxBodyCodePoints) => {
            const reconnected = hasBeenReady || attempt > 0;
            hasBeenReady = true;
            authorIdRef.current = nextAuthorId;
            setAuthorId(nextAuthorId);
            setNickname(nextNickname);
            setMaxBodyCodePoints(nextMaxBodyCodePoints);
            setConnection("ready");
            if (reconnected) {
              setNotice(RECONNECTED_NOTICE);
              window.setTimeout(() => {
                setNotice((current) => (current === RECONNECTED_NOTICE ? null : current));
              }, RECONNECTED_NOTICE_MS);
            }
          },
          onHistory: (messages) => dispatch({ type: "history.received", messages }),
          onHistoryEnd: () => {
            attempt = 0;
            setHistoryComplete(true);
          },
          onAck: (clientMessageId, message) => {
            clearAckTimer(ackTimers, clientMessageId);
            dispatch({ type: "message.ack", clientMessageId, message });
          },
          onCreated: (message) => {
            dispatch({ type: "message.created", message });
            if (message.authorId === authorIdRef.current) return;
            onIncoming();
          },
          onError: (error) => {
            const rateLimitedError = error.code === "RATE_LIMITED";
            const text = errorText(error);
            if (error.requestId !== null) {
              markPendingFailed(
                error.requestId,
                rateLimitedError ? SEND_FAILED_TEXT : text,
                error.code === "ID_CONFLICT",
              );
            }
            if (error.fatal) {
              fatal = true;
              setConnection(error.code === "CHAT_UNAVAILABLE" ? "unavailable" : "failed");
              setNotice(isKnownErrorCode(error.code) ? null : { tone: "error", text });
              socket.close();
              return;
            }
            if (rateLimitedError) {
              setRateLimited(true);
              window.setTimeout(() => setRateLimited(false), error.retryAfterMs ?? DEFAULT_RETRY_AFTER_MS);
              return;
            }
            setNotice({ tone: "error", text });
          },
        });
      });

      socket.addEventListener("close", (event) => {
        if (disposed) return;
        dispatch({ type: "connection.lost", failureMessage: CONNECTION_LOST_TEXT });
        clearAllAckTimers(ackTimers);
        if (fatal) return;
        if (!shouldReconnect(event.code)) {
          setConnection("failed");
          return;
        }
        const delay = reconnectDelayMs(attempt);
        if (delay === null) {
          setConnection("failed");
          return;
        }
        attempt += 1;
        setConnection("reconnecting");
        retryTimer = window.setTimeout(openSocket, delay);
      });

      socket.addEventListener("error", () => socket.close());
    };

    openSocket();
    return () => {
      disposed = true;
      if (retryTimer !== null) window.clearTimeout(retryTimer);
      const socket = socketRef.current;
      socketRef.current = null;
      if (socket !== null && socket.readyState < WebSocket.CLOSING) socket.close();
      clearAllAckTimers(ackTimers);
    };
  }, [markPendingFailed, onIncoming, reconnectKey, routeId, sessionId, started]);

  function sendBody(body: string, clientMessageId: string) {
    const socket = socketRef.current;
    if (connection !== "ready" || socket?.readyState !== WebSocket.OPEN) return false;
    socket.send(JSON.stringify({ v: 1, type: "message.send", clientMessageId, body }));
    const timer = window.setTimeout(() => markPendingFailed(clientMessageId, ACK_TIMEOUT_TEXT), ACK_TIMEOUT_MS);
    ackTimersRef.current.set(clientMessageId, timer);
    return true;
  }

  function sendMessage(body: string): boolean {
    if (rateLimited) return false;
    const clientMessageId = createClientId();
    if (!sendBody(body, clientMessageId)) return false;
    dispatch({
      type: "message.pending",
      message: { clientMessageId, body, createdAt: new Date().toISOString(), delivery: "sending" },
    });
    return true;
  }

  function retry(message: PendingMessage) {
    if (message.retryWithNewId === true) {
      const nextClientMessageId = createClientId();
      if (!sendBody(message.body, nextClientMessageId)) return;
      dispatch({
        type: "message.rekey",
        clientMessageId: message.clientMessageId,
        nextClientMessageId,
      });
      return;
    }
    if (sendBody(message.body, message.clientMessageId)) {
      dispatch({ type: "message.retry", clientMessageId: message.clientMessageId });
    }
  }

  function reconnect() {
    setNotice(null);
    setReconnectKey((key) => key + 1);
  }

  return {
    connection,
    historyComplete,
    authorId,
    nickname,
    maxBodyCodePoints,
    notice,
    rateLimited,
    messages: data.messages,
    pending: data.pending,
    sendMessage,
    retry,
    reconnect,
  };
}

function handleFrame(frame: ServerFrame, handlers: FrameHandlers) {
  switch (frame.type) {
    case "session.ready":
      handlers.onReady(frame.authorId, frame.nickname, frame.maxBodyCodePoints);
      break;
    case "history.batch":
      handlers.onHistory(frame.messages);
      break;
    case "history.end":
      handlers.onHistoryEnd();
      break;
    case "message.ack":
      handlers.onAck(frame.clientMessageId, frame.message);
      break;
    case "message.created":
      handlers.onCreated(frame.message);
      break;
    case "error":
      handlers.onError(frame);
      break;
  }
}

function getOrCreateSessionId(): string {
  try {
    const existing = sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (existing !== null) return existing;
    const created = createClientId();
    sessionStorage.setItem(SESSION_STORAGE_KEY, created);
    return created;
  } catch {
    return createClientId();
  }
}

function chatWebSocketUrl(routeId: string): string {
  const url = new URL(`/api/chat/rooms/${encodeURIComponent(routeId)}/stream`, window.location.href);
  url.protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function clearAckTimer(timers: AckTimers, clientMessageId: string) {
  const timer = timers.get(clientMessageId);
  if (timer !== undefined) window.clearTimeout(timer);
  timers.delete(clientMessageId);
}

function clearAllAckTimers(timers: AckTimers) {
  for (const timer of timers.values()) window.clearTimeout(timer);
  timers.clear();
}
