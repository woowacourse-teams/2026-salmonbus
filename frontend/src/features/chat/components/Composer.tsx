import { useState, type FormEvent, type KeyboardEvent, type SyntheticEvent } from "react";
import { MESSAGE_INPUT_LABEL, RATE_LIMITED_TEXT, SEND_LABEL, composerPlaceholder } from "../chatCopy";
import type { ConnectionState } from "../connectionPolicy";
import { codePointLength, limitCodePoints, normalizedBody } from "../messageBodyPolicy";
import * as styles from "./Composer.css";

interface ComposerProps {
  connection: ConnectionState;
  rateLimited: boolean;
  maxBodyCodePoints: number;
  onSend: (body: string) => boolean;
}

const CHARACTER_COUNT_VISIBLE_FROM = 150;

export function Composer({ connection, rateLimited, maxBodyCodePoints, onSend }: ComposerProps) {
  const [draft, setDraft] = useState("");
  const draftLength = codePointLength(draft);
  const canSend = connection === "ready" && !rateLimited && normalizedBody(draft, maxBodyCodePoints) !== null;

  function submit(event: FormEvent) {
    event.preventDefault();
    const body = normalizedBody(draft, maxBodyCodePoints);
    if (body === null || !onSend(body)) return;
    setDraft("");
  }

  return (
    <form className={styles.composer} onSubmit={submit}>
      <div role="status">{rateLimited && <p className={styles.hint}>{RATE_LIMITED_TEXT}</p>}</div>
      <div className={styles.row}>
        <textarea
          value={draft}
          className={styles.textarea}
          rows={1}
          maxLength={maxBodyCodePoints * 2}
          placeholder={composerPlaceholder(connection)}
          aria-label={MESSAGE_INPUT_LABEL}
          enterKeyHint="send"
          onChange={(event) => setDraft(limitCodePoints(event.target.value, maxBodyCodePoints))}
          onKeyDown={submitOnEnter}
        />
        <button
          type="submit"
          className={styles.sendButton}
          aria-label={SEND_LABEL}
          aria-disabled={!canSend}
          onMouseDown={keepComposerFocus}
          onClick={(event) => {
            if (!canSend) event.preventDefault();
          }}
        >
          <ArrowUpIcon />
        </button>
      </div>
      <span
        className={
          draftLength >= CHARACTER_COUNT_VISIBLE_FROM ? styles.characterCount.visible : styles.characterCount.hidden
        }
      >
        {draftLength}/{maxBodyCodePoints}
      </span>
    </form>
  );
}

function submitOnEnter(event: KeyboardEvent<HTMLTextAreaElement>) {
  if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
  event.preventDefault();
  event.currentTarget.form?.requestSubmit();
}

function keepComposerFocus(event: SyntheticEvent) {
  event.preventDefault();
}

function ArrowUpIcon() {
  return (
    <svg className={styles.sendIcon} viewBox="0 0 24 24" aria-hidden="true">
      <path d="m12 19V5m-6 6 6-6 6 6" />
    </svg>
  );
}
