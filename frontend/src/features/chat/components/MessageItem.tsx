import { RETRY_SEND_LABEL, deliveryLabel, messageTimeLabel } from "../chatCopy";
import type { ChatMessage } from "../chatProtocol";
import type { PendingMessage } from "../messagePolicy";
import * as styles from "./MessageItem.css";

type MessageItemProps =
  | { kind: "saved"; message: ChatMessage; own: boolean }
  | { kind: "pending"; message: PendingMessage; canRetry: boolean; onRetry: (message: PendingMessage) => void };

export function MessageItem(props: MessageItemProps) {
  if (props.kind === "pending") {
    const { message, canRetry, onRetry } = props;

    return (
      <article className={styles.message.own}>
        <div className={styles.bubbleRow}>
          <span className={styles.delivery[message.delivery]}>{deliveryLabel(message.delivery)}</span>
          <p className={styles.bubble.own}>{message.body}</p>
        </div>
        {message.delivery === "failed" && (
          <div className={styles.failureActions}>
            <span>{message.failureMessage}</span>
            <button type="button" className={styles.retryButton} onClick={() => onRetry(message)} disabled={!canRetry}>
              {RETRY_SEND_LABEL}
            </button>
          </div>
        )}
      </article>
    );
  }

  const { message, own } = props;

  return (
    <article className={own ? styles.message.own : styles.message.other}>
      {!own && <span className={styles.nickname}>{message.nickname}</span>}
      <div className={styles.bubbleRow}>
        {own && <time className={styles.time}>{messageTimeLabel(message.createdAt)}</time>}
        <p className={own ? styles.bubble.own : styles.bubble.other}>{message.body}</p>
        {!own && <time className={styles.time}>{messageTimeLabel(message.createdAt)}</time>}
      </div>
    </article>
  );
}
