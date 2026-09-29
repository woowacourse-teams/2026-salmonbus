import { useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { ChatAvailability } from "./chatAvailability";
import { useChatConnection } from "./useChatConnection";
import { useChatSheet } from "./useChatSheet";
import { useIsDesktop } from "./useIsDesktop";
import { useFollowNewest, useMessageScroll } from "./useMessageScroll";
import { useModalSheet } from "./useModalSheet";
import { useVisualViewport } from "./useVisualViewport";
import { ChatPeek } from "./components/ChatPeek";
import { ChatSheet } from "./components/ChatSheet";
import { Composer } from "./components/Composer";
import { MessageList } from "./components/MessageList";
import { NewMessagesButton } from "./components/NewMessagesButton";
import { StatusBanner } from "./components/StatusBanner";
import * as styles from "./ChatWidget.css";

interface ChatWidgetProps {
  availability: ChatAvailability;
}

const SHEET_ID = "route-chat-dialog";

export function ChatWidget({ availability }: ChatWidgetProps) {
  const [started, setStarted] = useState(false);
  const isDesktop = useIsDesktop();
  const layerRef = useRef<HTMLDivElement | null>(null);
  useVisualViewport(layerRef);
  const sheet = useChatSheet(isDesktop);
  const scroll = useMessageScroll(sheet.position);
  const chat = useChatConnection({
    routeId: availability.routeId,
    initialMaxBodyCodePoints: availability.maxBodyCodePoints,
    started,
    onIncoming: scroll.countIncoming,
  });
  useFollowNewest(scroll, chat.historyComplete, chat.messages.length, chat.pending.length);
  const modal = sheet.position === "full" && !isDesktop;
  useModalSheet(modal);

  function openChat() {
    setStarted(true);
    sheet.open();
    scroll.clearUnread();
  }

  return createPortal(
    <div ref={layerRef} className={`${styles.layer} amp-mask`} data-amp-mask="true">
      {sheet.position === "collapsed" && (
        <ChatPeek
          launcherRef={sheet.launcherRef}
          sheetId={SHEET_ID}
          displayName={availability.displayName}
          unread={scroll.unread}
          onOpen={openChat}
        />
      )}
      <ChatSheet
        sheetRef={sheet.sheetRef}
        id={SHEET_ID}
        position={sheet.position}
        modal={modal}
        connection={started ? chat.connection : "idle"}
        dragEnabled={!isDesktop}
        displayName={availability.displayName}
        nickname={chat.nickname}
        onMove={sheet.move}
        onToggleSize={sheet.toggleSize}
      >
        <StatusBanner notice={chat.notice} connection={chat.connection} onReconnect={chat.reconnect} />
        <MessageList
          listRef={scroll.listRef}
          displayName={availability.displayName}
          historyComplete={chat.historyComplete}
          messages={chat.messages}
          pending={chat.pending}
          authorId={chat.authorId}
          canRetry={chat.connection === "ready"}
          onRetry={chat.retry}
          onScroll={scroll.handleScroll}
        />
        {scroll.unreadBelow > 0 && <NewMessagesButton count={scroll.unreadBelow} onClick={scroll.revealNewest} />}
        <Composer
          connection={chat.connection}
          rateLimited={chat.rateLimited}
          maxBodyCodePoints={chat.maxBodyCodePoints}
          onSend={chat.sendMessage}
          onFocus={sheet.expandForInput}
        />
      </ChatSheet>
    </div>,
    document.body,
  );
}
