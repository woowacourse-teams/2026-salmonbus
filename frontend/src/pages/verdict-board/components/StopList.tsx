import { useLayoutEffect, useRef, useState, type RefObject } from "react";
import type { ExpandableStopView, StopView } from "./displayPolicy";
import type { LiveVehicleView } from "./liveVehiclePolicy";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import { LiveBusTraker } from "./LiveBusTraker";
import { StopRow } from "./StopRow";
import type { StationArrivalDetails } from "./stationArrivalPolicy";
import type { RoutePosition } from "./TimelineMarker";
import type { StopSelection } from "./stopSelection";
import * as styles from "./StopList.css";

interface StopListProps {
  stops: StopView[];
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  entering: boolean;
  selectedSequence: number | null;
  scrollRequest: StopSelection | null;
  selectedVehicleKey: string | null;
  stationArrivals: StationArrivalDetails | null;
  onToggle: (sequence: number) => void;
  onVehicleSelect: (key: string) => void;
  scrollContainerRef: RefObject<HTMLElement | null>;
  onStopExpand: (stop: ExpandableStopView, rowPosition: number) => void;
}

const LIVE_MARKER_HEIGHT = 32;
const COLLIDING_VEHICLE_GAP = 8;
const SCROLL_TARGET_TOP_PADDING = 12;
const STOP_SCROLL_DURATION_MS = 320;
const EMPTY_ANCHOR_POSITIONS: ReadonlyMap<number, number> = new Map();

// 회차 지점은 노선의 끝이 아니라 반대 방향으로 이어지는 지점이라 축을 끊지 않는다.
function routePositionFor(stop: StopView, index: number, lastIndex: number): RoutePosition {
  if (stop.isTurnaround) return "middle";
  if (lastIndex === 0) return "only";
  if (index === 0) return "start";
  if (index === lastIndex) return "end";
  return "middle";
}

export function StopList({
  stops,
  liveVehicleViews,
  motion,
  entering,
  selectedSequence,
  scrollRequest,
  selectedVehicleKey,
  stationArrivals,
  onToggle,
  onVehicleSelect,
  scrollContainerRef,
  onStopExpand,
}: StopListProps) {
  const routeCanvasRef = useRef<HTMLDivElement>(null);
  const markerRefs = useRef(new Map<string, HTMLDivElement>());
  const anchorPositions = useTimelineAnchorPositions(routeCanvasRef, stops, selectedSequence);
  useStopScroll(routeCanvasRef, scrollContainerRef, scrollRequest);

  useLayoutEffect(() => {
    const offsets = collisionOffsetsFor(liveVehicleViews);
    const updatePositions = () => {
      motion.frames.forEach((frame) => {
        const marker = markerRefs.current.get(frame.key);
        if (marker === undefined) return;
        const centerY = timelinePositionFor(frame.sequencePosition, anchorPositions);
        marker.style.visibility = centerY === null ? "hidden" : "visible";
        if (centerY !== null) {
          marker.style.transform = `translate(-50%, ${centerY + (offsets.get(frame.key) ?? 0) - LIVE_MARKER_HEIGHT / 2}px)`;
        }
      });
    };
    updatePositions();
    return motion.subscribe(updatePositions);
  }, [anchorPositions, liveVehicleViews, motion]);

  const toggle = (stop: ExpandableStopView, rowPosition: number) => {
    if (selectedSequence !== stop.sequence) onStopExpand(stop, rowPosition);
    onToggle(stop.sequence);
  };

  return (
    <div ref={routeCanvasRef} className={styles.routeCanvas}>
      <ul className={entering ? styles.list.entering : styles.list.still}>
        {stops.map((stop, index) => (
          <StopRow
            key={stop.sequence}
            stop={stop}
            routePosition={routePositionFor(stop, index, stops.length - 1)}
            selected={selectedSequence === stop.sequence}
            expanded={stop.kind !== "passThrough" && selectedSequence === stop.sequence}
            stationArrivals={stationArrivals?.selection.stopSequence === stop.sequence ? stationArrivals : null}
            onToggle={(expandableStop) => toggle(expandableStop, index)}
          />
        ))}
      </ul>
      <div className={styles.liveVehicleOverlay}>
        {liveVehicleViews.map((view) => (
          <div
            key={view.key}
            ref={(marker) => {
              if (marker === null) markerRefs.current.delete(view.key);
              else markerRefs.current.set(view.key, marker);
            }}
            className={styles.liveVehicle}
            data-live-vehicle-key={view.key}
          >
            <LiveBusTraker
              vehicle={view.vehicle}
              selected={view.key === selectedVehicleKey}
              onSelect={() => onVehicleSelect(view.key)}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

function useStopScroll(
  routeCanvasRef: RefObject<HTMLDivElement | null>,
  scrollContainerRef: RefObject<HTMLElement | null>,
  scrollRequest: StopSelection | null,
) {
  useLayoutEffect(() => {
    const canvas = routeCanvasRef.current;
    if (canvas === null || scrollRequest === null) return;
    const row = canvas.querySelector<HTMLElement>(`[data-stop-sequence="${scrollRequest.sequence}"]`);
    if (row === null) return;
    const control = row.querySelector<HTMLButtonElement>("button[aria-expanded]") ?? row;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let frame: number | null = null;
    let interrupted = false;

    const cancelFrame = () => {
      if (frame === null) return;
      window.cancelAnimationFrame(frame);
      frame = null;
    };
    const interruptScroll = () => {
      interrupted = true;
      cancelFrame();
    };

    const scrollToRow = () => {
      if (interrupted) return;
      cancelFrame();
      const container = scrollContainerRef.current;
      if (container === null) return;
      const startTop = container.scrollTop;
      const startedAt = performance.now();
      const animate = (now: number) => {
        frame = null;
        if (interrupted) return;
        // 정류장이나 시트가 펼쳐지는 동안 달라지는 위치도 따라간다.
        const top = row.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop;
        const target = Math.min(
          Math.max(0, container.scrollHeight - container.clientHeight),
          Math.max(0, top - SCROLL_TARGET_TOP_PADDING),
        );
        const progress =
          reducedMotion || Math.abs(target - startTop) < 1
            ? 1
            : Math.max(0, Math.min(1, (now - startedAt) / STOP_SCROLL_DURATION_MS));
        const eased = 1 - (1 - progress) ** 3;
        container.scrollTo({ top: startTop + (target - startTop) * eased, behavior: "instant" });
        if (progress < 1) frame = window.requestAnimationFrame(animate);
        else control.focus({ preventScroll: true });
      };
      animate(startedAt);
    };
    // 방향을 바꾸어 목록을 새로 만들 때는 부모의 스크롤 영역도 연결된 뒤 시작한다.
    frame = window.requestAnimationFrame(scrollToRow);

    // 앞서 펼친 행이 접히는 동안 순번의 실제 위치가 바뀔 수 있어 마지막 레이아웃도 맞춘다.
    const scrollAfterExpansion = (event: TransitionEvent) => {
      if (frame === null && (event.propertyName === "grid-template-rows" || event.propertyName === "min-height"))
        scrollToRow();
    };
    canvas.addEventListener("transitionend", scrollAfterExpansion);
    // 이동 중 다른 조작을 시작하면 멈추고 포커스도 가져오지 않는다.
    const interruptEvents = ["wheel", "touchstart", "pointerdown", "keydown"] as const;
    interruptEvents.forEach((event) =>
      window.addEventListener(event, interruptScroll, { passive: true, capture: true }),
    );
    return () => {
      interrupted = true;
      cancelFrame();
      canvas.removeEventListener("transitionend", scrollAfterExpansion);
      interruptEvents.forEach((event) => window.removeEventListener(event, interruptScroll, true));
    };
  }, [routeCanvasRef, scrollContainerRef, scrollRequest]);
}

function useTimelineAnchorPositions(
  routeCanvasRef: RefObject<HTMLDivElement | null>,
  stops: StopView[],
  selectedSequence: number | null,
): ReadonlyMap<number, number> {
  const [anchorPositions, setAnchorPositions] = useState(EMPTY_ANCHOR_POSITIONS);

  useLayoutEffect(() => {
    const routeCanvas = routeCanvasRef.current;
    if (routeCanvas === null) return;
    let measurementFrame: number | null = null;

    const measureAnchorPositions = () => {
      measurementFrame = null;
      const canvasBounds = routeCanvas.getBoundingClientRect();
      const nextAnchorPositions = new Map<number, number>();
      routeCanvas.querySelectorAll<HTMLElement>("[data-timeline-sequence]").forEach((anchor) => {
        const sequence = Number(anchor.dataset.timelineSequence);
        if (!Number.isInteger(sequence)) return;
        const bounds = anchor.getBoundingClientRect();
        nextAnchorPositions.set(sequence, bounds.top - canvasBounds.top + bounds.height / 2);
      });
      setAnchorPositions((current) =>
        hasEqualAnchorPositions(current, nextAnchorPositions) ? current : nextAnchorPositions,
      );
    };

    const scheduleMeasurement = () => {
      if (measurementFrame === null) measurementFrame = window.requestAnimationFrame(measureAnchorPositions);
    };
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(scheduleMeasurement);
    observer?.observe(routeCanvas);
    routeCanvas.querySelectorAll("[data-stop-sequence]").forEach((row) => observer?.observe(row));
    window.addEventListener("resize", scheduleMeasurement);
    scheduleMeasurement();

    return () => {
      if (measurementFrame !== null) window.cancelAnimationFrame(measurementFrame);
      observer?.disconnect();
      window.removeEventListener("resize", scheduleMeasurement);
    };
  }, [routeCanvasRef, selectedSequence, stops]);

  return anchorPositions;
}

function hasEqualAnchorPositions(current: ReadonlyMap<number, number>, next: ReadonlyMap<number, number>): boolean {
  if (current.size !== next.size) return false;
  for (const [sequence, position] of next) {
    if (current.get(sequence) !== position) return false;
  }
  return true;
}

function timelinePositionFor(position: number, anchors: ReadonlyMap<number, number>): number | null {
  let previous: [number, number] | null = null;
  for (const [sequence, centerY] of anchors) {
    if (sequence === position) return centerY;
    if (sequence > position) {
      if (previous === null) return centerY;
      const [fromSequence, fromY] = previous;
      return fromY + ((position - fromSequence) / (sequence - fromSequence)) * (centerY - fromY);
    }
    previous = [sequence, centerY];
  }
  return previous?.[1] ?? null;
}

function collisionOffsetsFor(views: LiveVehicleView[]): ReadonlyMap<string, number> {
  const groups = new Map<string, string[]>();
  views.forEach((view) => {
    const segment = view.motionSegment;
    const group = `${segment.fromStopSequence}:${segment.toStopSequence}:${segment.startProgress}:${segment.endProgress}`;
    const keys = groups.get(group) ?? [];
    keys.push(view.key);
    groups.set(group, keys);
  });
  const offsets = new Map<string, number>();
  groups.forEach((keys) => {
    keys.forEach((key, index) => offsets.set(key, (index - (keys.length - 1) / 2) * COLLIDING_VEHICLE_GAP));
  });
  return offsets;
}
