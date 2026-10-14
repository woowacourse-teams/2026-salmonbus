import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";

export interface LiveVehicleMotionFrame {
  key: string;
  sequencePosition: number;
}

export interface LiveVehicleMotionStore {
  readonly frames: readonly LiveVehicleMotionFrame[];
  subscribe: (listener: () => void) => () => void;
}

interface MotionTarget {
  start: number;
  end: number;
  durationMs: number;
}

interface MotionBase extends MotionTarget {
  signature: string;
  startedAt: number;
}

type VehicleMotion = (MotionBase & { kind: "moving" }) | (MotionBase & { kind: "correcting"; next: MotionTarget });

interface MotionSample {
  motion: VehicleMotion;
  position: number;
  active: boolean;
}

interface LiveVehicleMotionController extends LiveVehicleMotionStore {
  update: (views: readonly LiveVehicleView[], durationMs: number, now: number) => void;
  advance: (now: number) => boolean;
  setReducedMotion: (reducedMotion: boolean, now: number) => void;
  clear: () => void;
}

const SERVER_CORRECTION_DURATION_MS = 300;
const POSITION_EPSILON = 0.000_001;
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

// 지도와 목록은 같은 순번 좌표를 구독하고, 각자의 화면 좌표로만 변환한다.
export function createLiveVehicleMotionStore(): LiveVehicleMotionController {
  let motions = new Map<string, VehicleMotion>();
  let frames: readonly LiveVehicleMotionFrame[] = [];
  let reducedMotion = false;
  const listeners = new Set<() => void>();

  function publish(nextFrames: readonly LiveVehicleMotionFrame[]) {
    if (
      frames.length === nextFrames.length &&
      frames.every((frame, index) => {
        const next = nextFrames[index];
        return next !== undefined && frame.key === next.key && frame.sequencePosition === next.sequencePosition;
      })
    ) {
      return;
    }
    frames = nextFrames;
    listeners.forEach((listener) => listener());
  }

  function advance(now: number): boolean {
    let active = false;
    const nextFrames: LiveVehicleMotionFrame[] = [];
    motions.forEach((motion, key) => {
      const sample = motionSampleFor(motion, now);
      motions.set(key, sample.motion);
      nextFrames.push({ key, sequencePosition: sample.position });
      active ||= sample.active;
    });
    publish(nextFrames);
    return active;
  }

  return {
    get frames() {
      return frames;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    update(views, durationMs, now) {
      const nextMotions = new Map<string, VehicleMotion>();
      views.forEach((view) => {
        const target = targetFor(view, durationMs);
        const signature = signatureFor(view);
        const previous = motions.get(view.key);
        const sample = previous === undefined ? null : motionSampleFor(previous, now);

        if (reducedMotion) {
          nextMotions.set(view.key, movingMotionFor(signature, target.end, target.end, 0, now));
        } else if (sample === null) {
          nextMotions.set(view.key, movingMotionFor(signature, target.start, target.end, target.durationMs, now));
        } else if (sample.motion.signature === signature) {
          // 좌석 갱신이나 같은 구간 응답은 이미 흐른 시간과 끝점을 유지한다.
          nextMotions.set(view.key, sample.motion);
        } else if (sample.motion.kind === "correcting") {
          const remainingMs = Math.max(0, sample.motion.startedAt + sample.motion.durationMs - now);
          nextMotions.set(view.key, {
            kind: "correcting",
            signature,
            start: sample.position,
            end: target.start,
            durationMs: remainingMs,
            startedAt: now,
            next: target,
          });
        } else if (target.end < sample.position - POSITION_EPSILON) {
          nextMotions.set(view.key, {
            kind: "correcting",
            signature,
            start: sample.position,
            end: target.start,
            durationMs: SERVER_CORRECTION_DURATION_MS,
            startedAt: now,
            next: target,
          });
        } else {
          // 새 phase도 지금 실제로 표시하는 위치에서 이어 움직인다.
          nextMotions.set(view.key, movingMotionFor(signature, sample.position, target.end, target.durationMs, now));
        }
      });
      motions = nextMotions;
      advance(now);
    },
    advance,
    setReducedMotion(next, now) {
      reducedMotion = next;
      if (next) {
        motions.forEach((motion, key) => {
          const target = motion.kind === "correcting" ? motion.next.end : motion.end;
          motions.set(key, movingMotionFor(motion.signature, target, target, 0, now));
        });
      }
      advance(now);
    },
    clear() {
      motions.clear();
      publish([]);
    },
  };
}

export function useLiveVehicleMotion(views: LiveVehicleView[], durationMs: number): LiveVehicleMotionStore {
  const [store] = useState(createLiveVehicleMotionStore);
  const frameRef = useRef<number | null>(null);

  const animate = useCallback(() => {
    if (frameRef.current !== null) return;
    const draw = (now: number) => {
      frameRef.current = null;
      if (store.advance(now)) frameRef.current = window.requestAnimationFrame(draw);
    };
    frameRef.current = window.requestAnimationFrame(draw);
  }, [store]);

  useLayoutEffect(() => {
    const now = performance.now();
    store.setReducedMotion(window.matchMedia(REDUCED_MOTION_QUERY).matches, now);
    store.update(views, durationMs, now);
    animate();
  }, [animate, durationMs, store, views]);

  useEffect(() => {
    const preference = window.matchMedia(REDUCED_MOTION_QUERY);
    const updateMotionPreference = (event: MediaQueryListEvent) => {
      store.setReducedMotion(event.matches, performance.now());
      animate();
    };
    preference.addEventListener("change", updateMotionPreference);
    return () => {
      preference.removeEventListener("change", updateMotionPreference);
      if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
      store.clear();
    };
  }, [animate, store]);

  return store;
}

function targetFor(view: LiveVehicleView, durationMs: number): MotionTarget {
  const { fromStopSequence, toStopSequence, startProgress, endProgress } = view.motionSegment;
  return {
    start: interpolatedPositionFor(fromStopSequence, toStopSequence, startProgress),
    end: interpolatedPositionFor(fromStopSequence, toStopSequence, endProgress),
    durationMs: Math.max(0, durationMs),
  };
}

function signatureFor(view: LiveVehicleView): string {
  const { fromStopSequence, toStopSequence, startProgress, endProgress } = view.motionSegment;
  return `${view.vehicle.phase}:${fromStopSequence}:${toStopSequence}:${startProgress}:${endProgress}`;
}

function movingMotionFor(
  signature: string,
  start: number,
  end: number,
  durationMs: number,
  startedAt: number,
): VehicleMotion {
  return { kind: "moving", signature, start, end, durationMs, startedAt };
}

function motionSampleFor(motion: VehicleMotion, now: number): MotionSample {
  const elapsed = Math.max(0, now - motion.startedAt);
  if (motion.kind === "correcting" && elapsed >= motion.durationMs) {
    const next = movingMotionFor(
      motion.signature,
      motion.next.start,
      motion.next.end,
      motion.next.durationMs,
      motion.startedAt + motion.durationMs,
    );
    return motionSampleFor(next, now);
  }

  const progress = motion.durationMs === 0 ? 1 : Math.min(1, elapsed / motion.durationMs);
  const easedProgress = motion.kind === "correcting" ? 1 - (1 - progress) ** 3 : progress;
  return {
    motion,
    position: interpolatedPositionFor(motion.start, motion.end, easedProgress),
    active: progress < 1 && (motion.kind === "correcting" || motion.start !== motion.end),
  };
}

function interpolatedPositionFor(from: number, to: number, progress: number): number {
  return from + (to - from) * progress;
}
