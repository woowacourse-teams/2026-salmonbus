import type { RefObject } from "react";
import salmongSmile from "@/shared/assets/images/salmong-logo/salmong-smile.png";
import { EMPTY_ROOM_HINT, EMPTY_ROOM_TITLE, HISTORY_LOADING_LABEL, messageLogLabel } from "../chatCopy";
import type { ChatMessage } from "../chatProtocol";
import type { PendingMessage } from "../messagePolicy";
import { MessageItem } from "./MessageItem";
import * as styles from "./MessageList.css";

interface MessageListProps {
  listRef: RefObject<HTMLDivElement | null>;
  displayName: string;
  historyComplete: boolean;
  stalled: boolean;
  messages: ChatMessage[];
  pending: PendingMessage[];
  authorId: string | null;
  canRetry: boolean;
  onRetry: (message: PendingMessage) => void;
  onScroll: () => void;
}

export function MessageList({
  listRef,
  displayName,
  historyComplete,
  stalled,
  messages,
  pending,
  authorId,
  canRetry,
  onRetry,
  onScroll,
}: MessageListProps) {
  return (
    <div
      ref={listRef}
      className={styles.list}
      role="log"
      aria-live="polite"
      aria-label={messageLogLabel(displayName)}
      tabIndex={0}
      onScroll={onScroll}
    >
      {!historyComplete && messages.length === 0 ? (
        !stalled && <MessageSkeleton />
      ) : messages.length === 0 && pending.length === 0 ? (
        <EmptyRoom />
      ) : (
        <div className={styles.messages}>
          {messages.map((message) => (
            <MessageItem key={message.id} kind="saved" message={message} own={message.authorId === authorId} />
          ))}
          {pending.map((message) => (
            <MessageItem
              key={message.clientMessageId}
              kind="pending"
              message={message}
              canRetry={canRetry}
              onRetry={onRetry}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function MessageSkeleton() {
  return (
    <div className={styles.skeleton} aria-label={HISTORY_LOADING_LABEL}>
      <span className={styles.skeletonBar.first} />
      <span className={styles.skeletonBar.second} />
      <span className={styles.skeletonBar.third} />
    </div>
  );
}

function EmptyRoom() {
  return (
    <div className={styles.empty}>
      <img className={styles.emptyImage} src={salmongSmile} alt="" />
      <strong className={styles.emptyTitle}>{EMPTY_ROOM_TITLE}</strong>
      <span>{EMPTY_ROOM_HINT}</span>
    </div>
  );
}
