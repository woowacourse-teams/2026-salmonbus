import {
  useCallback,
  useEffect,
  useRef,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type RefObject,
} from "react";
import {
  DRAG_START_DISTANCE_PX,
  VELOCITY_WINDOW_MS,
  draggedSheetOffset,
  releaseVelocity,
  sheetPositionAfterRelease,
  type OpenSheetPosition,
  type PointerSample,
  type SheetPosition,
} from "./sheetPolicy";
import { readViewport } from "./useVisualViewport";

const CLICK_AFTER_DRAG_MS = 400;

interface DragSession {
  pointerId: number;
  from: OpenSheetPosition;
  startY: number;
  viewportHeight: number;
  dragging: boolean;
  samples: PointerSample[];
}

interface SheetDragOptions {
  sheetRef: RefObject<HTMLElement | null>;
  position: SheetPosition;
  enabled: boolean;
  onSettle(next: SheetPosition): void;
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
  const draggedAtRef = useRef<number | null>(null);

  const restoreSheet = useCallback(() => {
    const sheet = sheetRef.current;
    if (sheet === null) return;
    sheet.style.transform = "";
    sheet.style.transition = "";
  }, [sheetRef]);

  const cancelDrag = useCallback(() => {
    if (sessionRef.current === null) return;
    sessionRef.current = null;
    restoreSheet();
  }, [restoreSheet]);

  useEffect(() => {
    window.addEventListener("blur", cancelDrag);
    return () => window.removeEventListener("blur", cancelDrag);
  }, [cancelDrag]);

  useEffect(() => {
    if (!enabled || position === "collapsed") cancelDrag();
  }, [cancelDrag, enabled, position]);

  function onPointerDown(event: ReactPointerEvent<HTMLElement>) {
    if (!enabled || position === "collapsed" || !event.isPrimary) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (sessionRef.current !== null) restoreSheet();
    sessionRef.current = {
      pointerId: event.pointerId,
      from: position,
      startY: event.clientY,
      viewportHeight: readViewport().height,
      dragging: false,
      samples: [{ time: event.timeStamp, y: event.clientY }],
    };
  }

  function onPointerMove(event: ReactPointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    const sheet = sheetRef.current;
    if (session === null || sheet === null || event.pointerId !== session.pointerId) return;

    const distanceY = event.clientY - session.startY;
    if (!session.dragging) {
      if (Math.abs(distanceY) <= DRAG_START_DISTANCE_PX) return;
      session.dragging = true;
      event.currentTarget.setPointerCapture(event.pointerId);
      sheet.style.transition = "none";
    }

    session.samples = [
      ...session.samples.filter((sample) => event.timeStamp - sample.time <= VELOCITY_WINDOW_MS),
      { time: event.timeStamp, y: event.clientY },
    ];
    sheet.style.transform = `translateY(${draggedSheetOffset(session.from, session.viewportHeight, distanceY)}px)`;
  }

  function onPointerUp(event: ReactPointerEvent<HTMLElement>) {
    const session = sessionRef.current;
    if (session === null || event.pointerId !== session.pointerId) return;
    sessionRef.current = null;
    if (!session.dragging) return;

    draggedAtRef.current = event.timeStamp;
    const next = sheetPositionAfterRelease({
      from: session.from,
      viewportHeight: session.viewportHeight,
      distanceY: event.clientY - session.startY,
      velocityY: releaseVelocity(session.samples, { time: event.timeStamp, y: event.clientY }),
    });
    restoreSheet();
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
