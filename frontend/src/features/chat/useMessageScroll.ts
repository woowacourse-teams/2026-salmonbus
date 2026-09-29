import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import type { SheetPosition } from "./sheetPolicy";

interface ScrollAnchor {
  listRef: RefObject<HTMLDivElement | null>;
  atBottomRef: RefObject<boolean>;
}

const BOTTOM_TOLERANCE_PX = 40;

export function useMessageScroll(position: SheetPosition) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const atBottomRef = useRef(true);
  const distanceFromBottomRef = useRef(0);
  const positionRef = useRef(position);
  const [unread, setUnread] = useState(0);
  const [unreadBelow, setUnreadBelow] = useState(0);

  useEffect(() => {
    const list = listRef.current;
    if (list === null) return;
    const listObserver = new ResizeObserver(() => {
      const distance = atBottomRef.current ? 0 : distanceFromBottomRef.current;
      list.scrollTop = list.scrollHeight - list.clientHeight - distance;
    });
    const contentObserver = new ResizeObserver(() => {
      if (atBottomRef.current) list.scrollTop = list.scrollHeight - list.clientHeight;
    });
    const observeContent = () => {
      for (const child of list.children) contentObserver.observe(child);
    };
    const childObserver = new MutationObserver(observeContent);
    listObserver.observe(list);
    observeContent();
    childObserver.observe(list, { childList: true });
    return () => {
      listObserver.disconnect();
      contentObserver.disconnect();
      childObserver.disconnect();
    };
  }, []);

  useLayoutEffect(() => {
    const opened = positionRef.current === "collapsed" && position !== "collapsed";
    positionRef.current = position;
    const list = listRef.current;
    if (!opened || list === null) return;
    atBottomRef.current = true;
    scrollToBottom(list, false);
  }, [position]);

  const countIncoming = useCallback(() => {
    if (positionRef.current === "collapsed") {
      setUnread((count) => count + 1);
    } else if (!atBottomRef.current) {
      setUnreadBelow((count) => count + 1);
    }
  }, []);

  function handleScroll() {
    const list = listRef.current;
    if (list === null) return;
    const distance = list.scrollHeight - list.scrollTop - list.clientHeight;
    const atBottom = distance < BOTTOM_TOLERANCE_PX;
    distanceFromBottomRef.current = distance;
    atBottomRef.current = atBottom;
    if (atBottom) setUnreadBelow(0);
  }

  function revealNewest() {
    const list = listRef.current;
    if (list === null) return;
    atBottomRef.current = true;
    setUnreadBelow(0);
    scrollToBottom(list, true);
  }

  function clearUnread() {
    setUnread(0);
    setUnreadBelow(0);
  }

  return { listRef, atBottomRef, unread, unreadBelow, countIncoming, handleScroll, revealNewest, clearUnread };
}

export function useFollowNewest(
  { listRef, atBottomRef }: ScrollAnchor,
  historyComplete: boolean,
  messages: readonly unknown[],
  pending: readonly unknown[],
) {
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!historyComplete || list === null) return;
    scrollToBottom(list, false);
  }, [historyComplete, listRef]);

  useLayoutEffect(() => {
    const list = listRef.current;
    if (list === null || !atBottomRef.current) return;
    scrollToBottom(list, false);
  }, [atBottomRef, listRef, messages, pending]);
}

function scrollToBottom(element: HTMLElement, smooth: boolean) {
  element.scrollTo({ top: element.scrollHeight, behavior: smooth ? "smooth" : "auto" });
}
