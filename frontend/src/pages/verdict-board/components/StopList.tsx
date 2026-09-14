import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import type { StopView } from "../displayPolicy";
import type { LiveVehicleView } from "../liveVehiclePolicy";
import { LiveBusTraker } from "./LiveBusTraker";
import { StopRow } from "./StopRow";
import type { RoutePosition } from "./TimelineMarker";
import * as styles from "./StopList.css";

interface StopListProps {
  stops: StopView[];
  liveVehicleViews: LiveVehicleView[];
  liveMotionDurationMs: number;
  entering: boolean;
}

interface PositionedLiveVehicle {
  view: LiveVehicleView;
  startY: number;
  endY: number;
}

interface AnimatedLiveVehicleProps extends PositionedLiveVehicle {
  durationMs: number;
  reducedMotion: boolean;
}

interface PendingCorrection {
  segmentKey: string;
  startY: number;
  endY: number;
  durationMs: number;
}

const LIVE_MARKER_HEIGHT = 32;
const COLLIDING_VEHICLE_GAP = 8;
const POSITION_EPSILON = 0.5;
const LAYOUT_REBASE_DELAY_MS = 16;
const SERVER_CORRECTION_DURATION_MS = 300;
const SERVER_CORRECTION_EASING = "ease-out";
const LINEAR_EASING = "linear";
const EMPTY_ANCHOR_POSITIONS: ReadonlyMap<number, number> = new Map();

// 회차 지점은 노선의 끝이 아니라 반대 방향으로 이어지는 지점이라 축을 끊지 않는다.
function routePositionFor(stop: StopView, index: number, lastIndex: number): RoutePosition {
  if (stop.isTurnaround) return "middle";
  if (lastIndex === 0) return "only";
  if (index === 0) return "start";
  if (index === lastIndex) return "end";
  return "middle";
}

export function StopList({ stops, liveVehicleViews, liveMotionDurationMs, entering }: StopListProps) {
  const [expandedSequence, setExpandedSequence] = useState<number | null>(null);
  const routeCanvasRef = useRef<HTMLDivElement>(null);
  const anchorPositions = useTimelineAnchorPositions(routeCanvasRef, stops, expandedSequence);
  const positionedLiveVehicles = positionsFor(liveVehicleViews, anchorPositions);
  const reducedMotion = usePrefersReducedMotion();
  const toggle = (sequence: number) => setExpandedSequence((current) => (current === sequence ? null : sequence));

  return (
    <div ref={routeCanvasRef} className={styles.routeCanvas}>
      <ul className={entering ? styles.list.entering : styles.list.still}>
        {stops.map((stop, index) => (
          <StopRow
            key={stop.sequence}
            stop={stop}
            routePosition={routePositionFor(stop, index, stops.length - 1)}
            expanded={expandedSequence === stop.sequence}
            onToggle={() => toggle(stop.sequence)}
          />
        ))}
      </ul>
      <div className={styles.liveVehicleOverlay}>
        {positionedLiveVehicles.map((positionedVehicle) => (
          <AnimatedLiveVehicle
            key={positionedVehicle.view.key}
            {...positionedVehicle}
            durationMs={liveMotionDurationMs}
            reducedMotion={reducedMotion}
          />
        ))}
      </div>
    </div>
  );
}

function usePrefersReducedMotion(): boolean {
  const [reducedMotion, setReducedMotion] = useState(
    () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );

  useEffect(() => {
    const preference = window.matchMedia("(prefers-reduced-motion: reduce)");
    const updatePreference = (event: MediaQueryListEvent) => setReducedMotion(event.matches);

    preference.addEventListener("change", updatePreference);
    return () => preference.removeEventListener("change", updatePreference);
  }, []);

  return reducedMotion;
}

function useTimelineAnchorPositions(
  routeCanvasRef: RefObject<HTMLDivElement | null>,
  stops: StopView[],
  expandedSequence: number | null,
): ReadonlyMap<number, number> {
  const [anchorPositions, setAnchorPositions] = useState(EMPTY_ANCHOR_POSITIONS);

  useLayoutEffect(() => {
    const routeCanvas = routeCanvasRef.current;
    if (routeCanvas === null) return;

    let measurementFrame: number | undefined;

    const measureAnchorPositions = () => {
      measurementFrame = undefined;
      const canvasBounds = routeCanvas.getBoundingClientRect();
      const nextAnchorPositions = new Map<number, number>();

      routeCanvas.querySelectorAll<HTMLElement>("[data-timeline-sequence]").forEach((anchor) => {
        const sequence = Number(anchor.dataset.timelineSequence);
        if (!Number.isInteger(sequence)) return;

        const anchorBounds = anchor.getBoundingClientRect();
        const anchorCenterY = anchorBounds.top - canvasBounds.top + anchorBounds.height / 2;
        nextAnchorPositions.set(sequence, anchorCenterY);
      });

      setAnchorPositions((currentPositions) =>
        equalAnchorPositions(currentPositions, nextAnchorPositions) ? currentPositions : nextAnchorPositions,
      );
    };

    const scheduleMeasurement = () => {
      if (measurementFrame !== undefined) return;
      measurementFrame = window.requestAnimationFrame(measureAnchorPositions);
    };

    const resizeObserver =
      typeof ResizeObserver === "undefined" ? null : new ResizeObserver(() => scheduleMeasurement());

    resizeObserver?.observe(routeCanvas);
    routeCanvas.querySelectorAll("li").forEach((row) => resizeObserver?.observe(row));
    window.addEventListener("resize", scheduleMeasurement);
    scheduleMeasurement();

    return () => {
      if (measurementFrame !== undefined) window.cancelAnimationFrame(measurementFrame);
      resizeObserver?.disconnect();
      window.removeEventListener("resize", scheduleMeasurement);
    };
  }, [expandedSequence, routeCanvasRef, stops]);

  return anchorPositions;
}

function equalAnchorPositions(
  currentPositions: ReadonlyMap<number, number>,
  nextPositions: ReadonlyMap<number, number>,
): boolean {
  if (currentPositions.size !== nextPositions.size) return false;

  for (const [sequence, nextPosition] of nextPositions) {
    if (currentPositions.get(sequence) !== nextPosition) return false;
  }
  return true;
}

function positionsFor(
  liveVehicleViews: LiveVehicleView[],
  anchorPositions: ReadonlyMap<number, number>,
): PositionedLiveVehicle[] {
  const basePositions = liveVehicleViews.flatMap((view) => {
    const fromAnchorY = anchorPositions.get(view.motionSegment.fromStopSequence);
    const toAnchorY = anchorPositions.get(view.motionSegment.toStopSequence);
    if (fromAnchorY === undefined || toAnchorY === undefined) return [];

    return [
      {
        view,
        startY: interpolate(fromAnchorY, toAnchorY, view.motionSegment.startProgress),
        endY: interpolate(fromAnchorY, toAnchorY, view.motionSegment.endProgress),
      },
    ];
  });

  const vehicleCountByPosition = countVehiclesByPosition(basePositions);
  const vehicleIndexByPosition = new Map<string, number>();

  return basePositions.map((position) => {
    const positionKey = collisionPositionKey(position);
    const vehicleCount = vehicleCountByPosition.get(positionKey) ?? 1;
    const vehicleIndex = vehicleIndexByPosition.get(positionKey) ?? 0;
    const verticalOffset = centeredVerticalOffset(vehicleIndex, vehicleCount);

    vehicleIndexByPosition.set(positionKey, vehicleIndex + 1);
    return {
      ...position,
      startY: position.startY + verticalOffset,
      endY: position.endY + verticalOffset,
    };
  });
}

function countVehiclesByPosition(positions: PositionedLiveVehicle[]): ReadonlyMap<string, number> {
  const counts = new Map<string, number>();
  positions.forEach((position) => {
    const positionKey = collisionPositionKey(position);
    counts.set(positionKey, (counts.get(positionKey) ?? 0) + 1);
  });
  return counts;
}

function collisionPositionKey(position: PositionedLiveVehicle): string {
  return `${position.startY}:${position.endY}`;
}

function centeredVerticalOffset(vehicleIndex: number, vehicleCount: number): number {
  return (vehicleIndex - (vehicleCount - 1) / 2) * COLLIDING_VEHICLE_GAP;
}

function interpolate(start: number, end: number, progress: number): number {
  return start + (end - start) * progress;
}

function AnimatedLiveVehicle({ view, startY, endY, durationMs, reducedMotion }: AnimatedLiveVehicleProps) {
  const markerRef = useRef<HTMLDivElement>(null);
  const previousSegmentKeyRef = useRef<string | null>(null);
  const previousStartYRef = useRef(startY);
  const previousEndYRef = useRef(endY);
  const previousTargetYRef = useRef(endY);
  const correctionInProgressRef = useRef(false);
  const pendingCorrectionRef = useRef<PendingCorrection | null>(null);
  const correctionStartTimerRef = useRef<number | undefined>(undefined);
  const correctionFinishTimerRef = useRef<number | undefined>(undefined);
  const correctionContinuationTimerRef = useRef<number | undefined>(undefined);
  const [motion, setMotion] = useState({
    centerY: reducedMotion ? endY : startY,
    durationMs: 0,
    easing: LINEAR_EASING,
  });
  const segmentKey = motionSegmentKey(view);

  useEffect(() => {
    const marker = markerRef.current;
    if (marker === null) return;

    const previousSegmentKey = previousSegmentKeyRef.current;
    const previousTargetY = previousTargetYRef.current;
    const scheduledTransitions: number[] = [];
    const targetTransform = transformFor(endY);
    const nextCorrection = { segmentKey, startY, endY, durationMs } satisfies PendingCorrection;

    if (correctionContinuationTimerRef.current !== undefined) {
      window.clearTimeout(correctionContinuationTimerRef.current);
      correctionContinuationTimerRef.current = undefined;
    }

    if (correctionInProgressRef.current) {
      pendingCorrectionRef.current = nextCorrection;
      if (!reducedMotion) return;

      if (correctionStartTimerRef.current !== undefined) {
        window.clearTimeout(correctionStartTimerRef.current);
        correctionStartTimerRef.current = undefined;
      }
      if (correctionFinishTimerRef.current !== undefined) {
        window.clearTimeout(correctionFinishTimerRef.current);
        correctionFinishTimerRef.current = undefined;
      }
      correctionInProgressRef.current = false;
      pendingCorrectionRef.current = null;
    }

    if (
      !reducedMotion &&
      previousSegmentKey === segmentKey &&
      previousTargetY === endY &&
      marker.style.transform === targetTransform
    ) {
      return;
    }

    const scheduleTransition = (centerY: number, nextDurationMs: number, easing: string, delayMs = 0) => {
      const timer = window.setTimeout(() => {
        setMotion({ centerY, durationMs: nextDurationMs, easing });
      }, delayMs);
      scheduledTransitions.push(timer);
    };

    if (reducedMotion || startY === endY) {
      scheduleTransition(endY, 0, LINEAR_EASING);
    } else if (previousSegmentKey === segmentKey) {
      // 행 높이가 바뀌어도 차량의 구간 진행률은 유지하고 새 축 좌표에만 다시 맞춘다.
      const currentCenterY = currentCenterWithinOverlay(marker);
      const segmentProgress = progressBetween(previousStartYRef.current, previousEndYRef.current, currentCenterY);
      const rebasedCenterY = interpolate(startY, endY, segmentProgress);
      const remainingDurationMs = durationMs * (1 - segmentProgress);

      scheduleTransition(rebasedCenterY, 0, LINEAR_EASING);
      scheduleTransition(endY, remainingDurationMs, LINEAR_EASING, LAYOUT_REBASE_DELAY_MS);
    } else {
      const currentCenterY = currentCenterWithinOverlay(marker);

      // 새 phase의 끝점도 현재 표시 위치보다 뒤일 때만 서버 보정으로 간주한다.
      if (endY < currentCenterY - POSITION_EPSILON) {
        correctionInProgressRef.current = true;
        pendingCorrectionRef.current = nextCorrection;
        correctionStartTimerRef.current = window.setTimeout(() => {
          setMotion({ centerY: startY, durationMs: SERVER_CORRECTION_DURATION_MS, easing: SERVER_CORRECTION_EASING });
          correctionStartTimerRef.current = undefined;
        });
        correctionFinishTimerRef.current = window.setTimeout(() => {
          const latestCorrection = pendingCorrectionRef.current;
          correctionInProgressRef.current = false;
          pendingCorrectionRef.current = null;
          correctionFinishTimerRef.current = undefined;
          if (latestCorrection === null) return;

          // 보정 중 바뀐 행 높이는 보정이 끝난 뒤 최신 축 좌표에 한 번만 반영한다.
          setMotion({ centerY: latestCorrection.startY, durationMs: 0, easing: LINEAR_EASING });
          previousSegmentKeyRef.current = latestCorrection.segmentKey;
          previousStartYRef.current = latestCorrection.startY;
          previousEndYRef.current = latestCorrection.endY;
          previousTargetYRef.current = latestCorrection.endY;

          correctionContinuationTimerRef.current = window.setTimeout(() => {
            setMotion({
              centerY: latestCorrection.endY,
              durationMs: latestCorrection.durationMs,
              easing: LINEAR_EASING,
            });
            correctionContinuationTimerRef.current = undefined;
          }, LAYOUT_REBASE_DELAY_MS);
        }, SERVER_CORRECTION_DURATION_MS);
        return;
      } else {
        scheduleTransition(endY, durationMs, LINEAR_EASING);
      }
    }

    previousSegmentKeyRef.current = segmentKey;
    previousStartYRef.current = startY;
    previousEndYRef.current = endY;
    previousTargetYRef.current = endY;

    return () => scheduledTransitions.forEach((timer) => window.clearTimeout(timer));
  }, [durationMs, endY, reducedMotion, segmentKey, startY]);

  useEffect(
    () => () => {
      if (correctionStartTimerRef.current !== undefined) window.clearTimeout(correctionStartTimerRef.current);
      if (correctionFinishTimerRef.current !== undefined) window.clearTimeout(correctionFinishTimerRef.current);
      if (correctionContinuationTimerRef.current !== undefined) {
        window.clearTimeout(correctionContinuationTimerRef.current);
      }
    },
    [],
  );

  return (
    <div
      ref={markerRef}
      className={styles.liveVehicle}
      style={{
        transform: transformFor(motion.centerY),
        transitionProperty: reducedMotion ? "none" : "transform",
        transitionDuration: `${motion.durationMs}ms`,
        transitionTimingFunction: motion.easing,
      }}
    >
      <LiveBusTraker vehicle={view.vehicle} />
    </div>
  );
}

function motionSegmentKey(view: LiveVehicleView): string {
  const { fromStopSequence, toStopSequence, startProgress, endProgress } = view.motionSegment;
  return `${view.vehicle.phase}:${fromStopSequence}:${toStopSequence}:${startProgress}:${endProgress}`;
}

function transformFor(centerY: number): string {
  return `translate(-50%, ${centerY - LIVE_MARKER_HEIGHT / 2}px)`;
}

function currentCenterWithinOverlay(marker: HTMLElement): number {
  const overlay = marker.parentElement;
  if (overlay === null) return 0;

  const markerBounds = marker.getBoundingClientRect();
  const overlayBounds = overlay.getBoundingClientRect();
  return markerBounds.top - overlayBounds.top + markerBounds.height / 2;
}

function progressBetween(start: number, end: number, position: number): number {
  if (start === end) return 1;
  return Math.min(1, Math.max(0, (position - start) / (end - start)));
}
