import { useLayoutEffect, type RefObject } from "react";
import type { BoardSheetSnap } from "../components/boardSheetPolicy";

// 채팅 portal에는 표시 위치만 전달하고 연결·시트 상태는 기존 ChatWidget에 남긴다.
export function useBoardOverlayAnchors(
  pageRef: RefObject<HTMLDivElement | null>,
  snap: BoardSheetSnap,
  sheetHeight: number,
  viewportHeight: number,
) {
  useLayoutEffect(() => {
    const page = pageRef.current;
    if (page === null) return;
    const style = document.documentElement.style;
    const properties = [
      "--board-chat-top",
      "--board-chat-left",
      "--board-chat-right",
      "--board-chat-bottom",
      "--board-list-top",
      "--board-list-height",
      "--board-chat-layer-bottom",
    ];
    const previous = properties.map((name) => ({ name, value: style.getPropertyValue(name) }));
    const update = () => {
      const pageBounds = page.getBoundingClientRect();
      const sheet = page.querySelector<HTMLElement>('[aria-label="판정보드"]');
      const list = page.querySelector<HTMLElement>('[aria-label="판정보드 정류장 목록"]');
      const sheetTop = sheet?.getBoundingClientRect().top ?? pageBounds.top + viewportHeight - sheetHeight;
      style.setProperty("--board-chat-top", `${snap === "full" ? sheetTop + 29 : sheetTop - 52}px`);
      style.setProperty("--board-chat-left", snap === "full" ? "auto" : "18px");
      style.setProperty("--board-chat-right", snap === "full" ? "18px" : "auto");
      style.setProperty("--board-chat-bottom", "auto");
      if (list !== null) {
        const bounds = list.getBoundingClientRect();
        const height = snap === "minimum" ? Math.min(360, viewportHeight - 64) : bounds.height;
        const top = snap === "minimum" ? pageBounds.bottom - height - 32 : bounds.top;
        style.setProperty("--board-list-top", `${top}px`);
        style.setProperty("--board-list-height", `${height}px`);
        style.setProperty("--board-chat-layer-bottom", "auto");
      }
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(page);
    const sheet = page.querySelector('[aria-label="판정보드"]');
    if (sheet !== null) observer.observe(sheet);
    return () => {
      observer.disconnect();
      previous.forEach(({ name, value }) => {
        if (value === "") style.removeProperty(name);
        else style.setProperty(name, value);
      });
    };
  }, [pageRef, snap, sheetHeight, viewportHeight]);
}
