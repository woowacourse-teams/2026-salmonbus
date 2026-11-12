import { useLayoutEffect, useRef, useState, type RefObject } from "react";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import type { StopView } from "./displayPolicy";
import { LiveBusTraker } from "./LiveBusTraker";
import type { LiveVehicleView } from "./liveVehiclePolicy";
import * as styles from "./LiveVehicleOverlay.css";

interface LiveVehicleOverlayProps {
  routeCanvasRef: RefObject<HTMLDivElement | null>;
  stops: StopView[];
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  selectedVehicleKey: string | null;
  onVehicleSelect: (key: string) => void;
}

const LIVE_MARKER_HEIGHT = 32;
const COLLIDING_VEHICLE_GAP = 8;
const EMPTY_ANCHOR_POSITIONS: ReadonlyMap<number, number> = new Map();

export function LiveVehicleOverlay({
  routeCanvasRef,
  stops,
  liveVehicleViews,
  motion,
  selectedVehicleKey,
  onVehicleSelect,
}: LiveVehicleOverlayProps) {
  const markerRefs = useRef(new Map<string, HTMLDivElement>());
  const anchorPositions = useTimelineAnchorPositions(routeCanvasRef, stops);

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

  return (
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
  );
}

function useTimelineAnchorPositions(
  routeCanvasRef: RefObject<HTMLDivElement | null>,
  stops: StopView[],
): ReadonlyMap<number, number> {
  const [anchorPositions, setAnchorPositions] = useState(EMPTY_ANCHOR_POSITIONS);

  useLayoutEffect(() => {
    let measurementFrame: number | null = null;

    const measureAnchorPositions = () => {
      measurementFrame = null;
      const routeCanvas = routeCanvasRef.current;
      if (routeCanvas === null) return;
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
    // 첫 프레임에는 부모의 routeCanvas ref가 연결되어 정류장 행을 읽을 수 있다.
    measurementFrame = window.requestAnimationFrame(() => {
      const routeCanvas = routeCanvasRef.current;
      if (routeCanvas !== null && observer !== null) {
        observer.observe(routeCanvas);
        routeCanvas.querySelectorAll("[data-stop-sequence]").forEach((row) => observer.observe(row));
      }
      measureAnchorPositions();
    });
    window.addEventListener("resize", scheduleMeasurement);

    return () => {
      if (measurementFrame !== null) window.cancelAnimationFrame(measurementFrame);
      observer?.disconnect();
      window.removeEventListener("resize", scheduleMeasurement);
    };
  }, [routeCanvasRef, stops]);

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
