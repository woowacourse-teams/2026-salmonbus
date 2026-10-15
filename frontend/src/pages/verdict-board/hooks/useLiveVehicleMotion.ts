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

interface VehicleMotion extends MotionTarget {
  signature: string;
  startedAt: number;
}

interface MotionSample {
  position: number;
  active: boolean;
}

interface LiveVehicleMotionController extends LiveVehicleMotionStore {
  update: (views: readonly LiveVehicleView[], durationMs: number, now: number) => void;
  advance: (now: number) => boolean;
  setReducedMotion: (reducedMotion: boolean, now: number) => void;
  clear: () => void;
}

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

        if (previous !== undefined && previous.signature === signature) {
          // 좌석 갱신이나 같은 구간 응답은 이미 흐른 시간과 끝점을 유지한다.
          nextMotions.set(view.key, previous);
          return;
        }

        const start = sample?.position ?? target.start;
        // 뒤쪽 목표는 현재 위치에서 멈추고, 앞선 목표가 올 때 같은 위치에서 이어간다.
        const end = Math.max(start, target.end);
        nextMotions.set(
          view.key,
          movingMotionFor(signature, reducedMotion ? end : start, end, reducedMotion ? 0 : target.durationMs, now),
        );
      });
      motions = nextMotions;
      advance(now);
    },
    advance,
    setReducedMotion(next, now) {
      reducedMotion = next;
      if (next) {
        motions.forEach((motion, key) => {
          const target = motion.end;
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
  return { signature, start, end, durationMs, startedAt };
}

function motionSampleFor(motion: VehicleMotion, now: number): MotionSample {
  const elapsed = Math.max(0, now - motion.startedAt);
  const progress = motion.durationMs === 0 ? 1 : Math.min(1, elapsed / motion.durationMs);
  return {
    position: interpolatedPositionFor(motion.start, motion.end, progress),
    active: progress < 1 && motion.start !== motion.end,
  };
}

function interpolatedPositionFor(from: number, to: number, progress: number): number {
  return from + (to - from) * progress;
}
