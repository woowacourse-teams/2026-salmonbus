import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type RefObject,
} from "react";
import { vars } from "@/shared/styles/tokens.css";
import { MOBILE_MOTION_MEDIA } from "./chatLayout";
import {
  DRAG_START_DISTANCE_PX,
  draggedSheetHeight,
  settleSheet,
  sheetHeight,
  type SheetLayout,
  type SheetPosition,
} from "./sheetPolicy";
import { readViewport } from "./useVisualViewport";

const CLICK_AFTER_DRAG_MS = 400;
const HEIGHT_TRANSITION_CLEAR_MS = 400;

const heightTransitionTimers = new WeakMap<HTMLElement, number>();

interface DragSession {
  pointerId: number;
  originY: number;
  originHeight: number;
  startY: number;
  startHeight: number;
  latestY: number;
  viewportHeight: number;
  dragging: boolean;
}

interface SheetDragOptions {
  sheetRef: RefObject<HTMLElement | null>;
  position: SheetPosition;
  enabled: boolean;
  onSettle(next: SheetLayout): void;
}

export interface SheetDragHandlers {
  onPointerDown(event: ReactPointerEvent<HTMLElement>): void;
  onPointerMove(event: ReactPointerEvent<HTMLElement>): void;
  onPointerUp(event: ReactPointerEvent<HTMLElement>): void;
  onPointerCancel(event: ReactPointerEvent<HTMLElement>): void;
  onLostPointerCapture(event: ReactPointerEvent<HTMLElement>): void;
  onClickCapture(event: ReactMouseEvent<HTMLElement>): void;
}

export function useSheetDrag({ sheetRef, position, enabled, onSettle }: SheetDragOptions): SheetDragHandlers {
  const sessionRef = useRef<DragSession | null>(null);
  const frameRef = useRef<number | null>(null);
  const draggedAtRef = useRef<number | null>(null);
  const [settleCount, setSettleCount] = useState(0);

  const cancelFrame = useCallback(() => {
    if (frameRef.current === null) return;
    window.cancelAnimationFrame(frameRef.current);
    frameRef.current = null;
  }, []);

  const releaseSheet = useCallback(() => {
    cancelFrame();
    sheetRef.current?.style.removeProperty("height");
  }, [cancelFrame, sheetRef]);

  const cancelDrag = useCallback(() => {
    if (sessionRef.current === null) return;
    sessionRef.current = null;
    releaseSheet();
  }, [releaseSheet]);

  useEffect(() => {
    window.addEventListener("blur", cancelDrag);
    return () => window.removeEventListener("blur", cancelDrag);
  }, [cancelDrag]);

  useEffect(() => {
    if (!enabled || position === "collapsed") cancelDrag();
  }, [cancelDrag, enabled, position]);

  useLayoutEffect(() => {
    if (settleCount > 0) sheetRef.current?.style.removeProperty("height");
  }, [settleCount, sheetRef]);

  function onPointerDown(event: ReactPointerEvent<HTMLElement>) {
    const sheet = sheetRef.current;
    if (!enabled || position === "collapsed" || !event.isPrimary || sheet === null) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (sessionRef.current !== null) releaseSheet();
    const height = holdSheetHeight(sheet);
    sessionRef.current = {
      pointerId: event.pointerId,
      originY: event.clientY,
      originHeight: height,
      startY: event.clientY,
      startHeight: height,
      latestY: event.clientY,
      viewportHeight: readViewport().height,
      dragging: false,
    };
  }

  function onPointerMove(event: ReactPointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    const sheet = sheetRef.current;
    if (session === null || sheet === null || event.pointerId !== session.pointerId) return;

    if (!session.dragging) {
      if (Math.abs(event.clientY - session.startY) <= DRAG_START_DISTANCE_PX) return;
      session.dragging = true;
      session.startY = event.clientY;
      session.startHeight = holdSheetHeight(sheet);
      event.currentTarget.setPointerCapture(event.pointerId);
    }

    session.latestY = event.clientY;
    if (frameRef.current !== null) return;
    frameRef.current = window.requestAnimationFrame(() => {
      frameRef.current = null;
      const distanceY = session.latestY - session.startY;
      sheet.style.height = `${draggedSheetHeight(session.startHeight, session.viewportHeight, distanceY)}px`;
    });
  }

  function onPointerUp(event: ReactPointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    const sheet = sheetRef.current;
    if (session === null || event.pointerId !== session.pointerId) return;
    sessionRef.current = null;
    if (!session.dragging || sheet === null) {
      releaseSheet();
      return;
    }

    cancelFrame();
    draggedAtRef.current = event.timeStamp;
    const height = draggedSheetHeight(session.startHeight, session.viewportHeight, event.clientY - session.startY);
    const next = settleSheet({
      startHeight: session.originHeight,
      height: draggedSheetHeight(session.originHeight, session.viewportHeight, event.clientY - session.originY),
      viewportHeight: session.viewportHeight,
    });
    sheet.style.height = `${height}px`;
    if (next.open) {
      if (sheetHeight(next.snap, session.viewportHeight) !== height) transitionSheetHeight(sheet, vars.duration.spring);
      setSettleCount((count) => count + 1);
    }
    onSettle(next);
  }

  function onPointerCancel(event: ReactPointerEvent<HTMLElement>) {
    if (sessionRef.current?.pointerId !== event.pointerId) return;
    cancelDrag();
  }

  function onLostPointerCapture(event: ReactPointerEvent<HTMLElement>) {
    if (event.target !== event.currentTarget) return;
    onPointerCancel(event);
  }

  function onClickCapture(event: ReactMouseEvent<HTMLElement>) {
    const draggedAt = draggedAtRef.current;
    if (draggedAt === null) return;
    draggedAtRef.current = null;
    if (event.timeStamp - draggedAt > CLICK_AFTER_DRAG_MS) return;
    event.preventDefault();
    event.stopPropagation();
  }

  return {
    onPointerDown,
    onPointerMove,
    onPointerUp,
    onPointerCancel,
    onLostPointerCapture,
    onClickCapture,
  };
}

export function transitionSheetHeight(sheet: HTMLElement, duration: string) {
  stopSheetHeightTransition(sheet);
  if (!window.matchMedia(MOBILE_MOTION_MEDIA).matches) return;
  sheet.style.transition = `height ${duration} ${vars.easing.slide}`;
  heightTransitionTimers.set(
    sheet,
    window.setTimeout(() => stopSheetHeightTransition(sheet), HEIGHT_TRANSITION_CLEAR_MS),
  );
}

function holdSheetHeight(sheet: HTMLElement): number {
  const height = Math.round(sheet.getBoundingClientRect().height);
  if (heightTransitionTimers.has(sheet)) {
    stopSheetHeightTransition(sheet);
    sheet.style.height = `${height}px`;
  }
  return height;
}

export function stopSheetHeightTransition(sheet: HTMLElement) {
  window.clearTimeout(heightTransitionTimers.get(sheet));
  heightTransitionTimers.delete(sheet);
  sheet.style.removeProperty("transition");
}
