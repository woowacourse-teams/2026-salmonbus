import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
} from "react";
import type { BoardSheetSnap } from "../components/boardSheetPolicy";
import {
  BOARD_SHEET_DRAG_THRESHOLD,
  adjacentBoardSheetSnapFor,
  boardSheetHeightsFor,
  boundedBoardSheetHeightFor,
  keyboardBoardSheetSnapFor,
  settledBoardSheetSnapFor,
} from "../components/boardSheetPolicy";

interface BoardSheetOptions {
  snap: BoardSheetSnap;
  viewportHeight: number;
  onSnapChange: (snap: BoardSheetSnap) => void;
}

interface SheetGeometry {
  headerHeight: number;
  safeTop: number;
  safeBottom: number;
}

interface DragSession {
  pointerId: number;
  target: HTMLElement;
  originY: number;
  originHeight: number;
  latestY: number;
  latestTime: number;
  velocity: number;
  dragging: boolean;
}

const INTERACTIVE_HEADER_CONTENT =
  "button, a, input, select, textarea, [role='button'], [role='tab'], [contenteditable='true'], [data-sheet-no-drag]";
const CLICK_AFTER_DRAG_MS = 400;
const FLICK_IDLE_MS = 80;

export function useBoardSheet({ snap, viewportHeight, onSnapChange }: BoardSheetOptions) {
  const sheetRef = useRef<HTMLElement | null>(null);
  const headerRef = useRef<HTMLDivElement | null>(null);
  const safeTopRef = useRef<HTMLSpanElement | null>(null);
  const safeBottomRef = useRef<HTMLSpanElement | null>(null);
  const sessionRef = useRef<DragSession | null>(null);
  const frameRef = useRef<number | null>(null);
  const draggedAtRef = useRef<number | null>(null);
  const [geometry, setGeometry] = useState<SheetGeometry>({ headerHeight: 0, safeTop: 0, safeBottom: 0 });
  const [dragHeight, setDragHeight] = useState<number | null>(null);
  const heights = boardSheetHeightsFor({ viewportHeight, ...geometry });
  const height = dragHeight === null ? heights[snap] : boundedBoardSheetHeightFor(dragHeight, heights);

  const cancelFrame = useCallback(() => {
    if (frameRef.current === null) return;
    window.cancelAnimationFrame(frameRef.current);
    frameRef.current = null;
  }, []);

  const releasePointer = useCallback(() => {
    const session = sessionRef.current;
    sessionRef.current = null;
    if (session?.target.hasPointerCapture(session.pointerId)) session.target.releasePointerCapture(session.pointerId);
  }, []);

  const cancelDrag = useCallback(() => {
    cancelFrame();
    releasePointer();
    setDragHeight(null);
  }, [cancelFrame, releasePointer]);

  useLayoutEffect(() => {
    const header = headerRef.current;
    const safeTop = safeTopRef.current;
    const safeBottom = safeBottomRef.current;
    if (header === null || safeTop === null || safeBottom === null) return;

    const measure = () => {
      const next = {
        headerHeight: header.getBoundingClientRect().height,
        safeTop: safeTop.getBoundingClientRect().height,
        safeBottom: safeBottom.getBoundingClientRect().height,
      };
      setGeometry((previous) =>
        previous.headerHeight === next.headerHeight &&
        previous.safeTop === next.safeTop &&
        previous.safeBottom === next.safeBottom
          ? previous
          : next,
      );
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(header);
    observer.observe(safeTop);
    observer.observe(safeBottom);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    window.addEventListener("blur", cancelDrag);
    window.addEventListener("resize", cancelDrag);
    return () => {
      window.removeEventListener("blur", cancelDrag);
      window.removeEventListener("resize", cancelDrag);
      cancelFrame();
      releasePointer();
    };
  }, [cancelDrag, cancelFrame, releasePointer]);

  function startDrag(event: PointerEvent<HTMLElement>) {
    const sheet = sheetRef.current;
    if (!event.isPrimary || event.button !== 0 || sheet === null) return;
    if (event.target instanceof Element && event.target.closest(INTERACTIVE_HEADER_CONTENT)) return;
    cancelFrame();
    releasePointer();
    const originHeight = sheet.getBoundingClientRect().height;
    if (event.pointerType === "mouse") event.preventDefault();
    const target = event.target instanceof HTMLElement ? event.target : event.currentTarget;
    target.setPointerCapture(event.pointerId);
    sessionRef.current = {
      pointerId: event.pointerId,
      target,
      originY: event.clientY,
      originHeight,
      latestY: event.clientY,
      latestTime: event.timeStamp,
      velocity: 0,
      dragging: false,
    };
  }

  function moveDrag(event: PointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    if (session === null || session.pointerId !== event.pointerId) return;
    if (!session.dragging && Math.abs(event.clientY - session.originY) < BOARD_SHEET_DRAG_THRESHOLD) return;
    session.dragging = true;
    const elapsed = event.timeStamp - session.latestTime;
    if (elapsed > 0) session.velocity = (session.latestY - event.clientY) / elapsed;
    session.latestY = event.clientY;
    session.latestTime = event.timeStamp;
    if (frameRef.current !== null) return;
    frameRef.current = window.requestAnimationFrame(() => {
      frameRef.current = null;
      setDragHeight(boundedBoardSheetHeightFor(session.originHeight + session.originY - session.latestY, heights));
    });
  }

  function finishDrag(event: PointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    if (session === null || session.pointerId !== event.pointerId) return;
    cancelFrame();
    releasePointer();
    setDragHeight(null);
    if (!session.dragging) return;
    draggedAtRef.current = event.timeStamp;
    const releaseHeight = boundedBoardSheetHeightFor(session.originHeight + session.originY - event.clientY, heights);
    const velocity = event.timeStamp - session.latestTime > FLICK_IDLE_MS ? 0 : session.velocity;
    onSnapChange(settledBoardSheetSnapFor(releaseHeight, velocity, heights, snap));
  }

  function cancelPointerDrag(event: PointerEvent<HTMLElement>) {
    if (sessionRef.current?.pointerId === event.pointerId) cancelDrag();
  }

  function suppressClickAfterDrag(event: MouseEvent<HTMLElement>) {
    const draggedAt = draggedAtRef.current;
    if (draggedAt === null) return;
    draggedAtRef.current = null;
    if (event.timeStamp - draggedAt > CLICK_AFTER_DRAG_MS) return;
    event.preventDefault();
    event.stopPropagation();
  }

  function changeSnapFromKey(event: KeyboardEvent<HTMLElement>) {
    const next = keyboardBoardSheetSnapFor(event.key, snap, heights);
    if (next === null) return;
    event.preventDefault();
    cancelDrag();
    onSnapChange(next);
  }

  function cycleSnap() {
    const next = adjacentBoardSheetSnapFor(snap, heights, "larger");
    onSnapChange(next === snap ? "minimum" : next);
  }

  return {
    sheetRef,
    headerRef,
    safeTopRef,
    safeBottomRef,
    height,
    dragging: dragHeight !== null,
    dragHandlers: {
      onPointerDown: startDrag,
      onPointerMove: moveDrag,
      onPointerUp: finishDrag,
      onPointerCancel: cancelPointerDrag,
      onLostPointerCapture: cancelPointerDrag,
      onClickCapture: suppressClickAfterDrag,
    },
    onHandleKeyDown: changeSnapFromKey,
    onHandleClick: cycleSnap,
  };
}
