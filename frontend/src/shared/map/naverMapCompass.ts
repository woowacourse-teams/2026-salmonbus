import { isMapRotated, rotationFromNorth } from "./mapRotation";
import type { NaverMap, NaverMaps } from "./naverSdk";

const RESET_DURATION_MS = 250;
const interruptEvents = ["pointerdown", "touchstart", "wheel", "keydown"] as const;

export function bindNaverMapCompass(
  { maps, map, element }: { maps: NaverMaps; map: NaverMap; element: HTMLElement },
  onRotationChange: (rotation: number) => void,
  onError: () => void,
) {
  let resetFrame: number | null = null;
  let reportFrame: number | null = null;
  let lastRotation: number | undefined;
  let destroyed = false;
  const listeners: unknown[] = [];

  function reportRotation() {
    if (destroyed) return;
    const rotation = map.getRotation();
    if (!Number.isFinite(rotation) || rotation === lastRotation) return;
    lastRotation = rotation;
    onRotationChange(rotation);
  }

  function scheduleReport() {
    if (destroyed || reportFrame !== null) return;
    reportFrame = window.requestAnimationFrame(() => {
      reportFrame = null;
      runMapAction(reportRotation);
    });
  }

  function cancelReset() {
    if (resetFrame !== null) window.cancelAnimationFrame(resetFrame);
    resetFrame = null;
    interruptEvents.forEach((event) => element.removeEventListener(event, cancelReset, true));
  }

  function destroy() {
    if (destroyed) return;
    destroyed = true;
    cancelReset();
    if (reportFrame !== null) window.cancelAnimationFrame(reportFrame);
    if (window.naver?.maps !== maps) return;
    for (const listener of listeners) {
      try {
        maps.Event.removeListener(listener);
      } catch {
        // SDK가 먼저 리스너를 해제한 경우에도 나머지 정리는 계속한다.
      }
    }
  }

  function runMapAction(action: () => void) {
    if (destroyed) return;
    if (window.naver?.maps !== maps) {
      destroy();
      return;
    }
    try {
      action();
    } catch {
      destroy();
      onError();
    }
  }

  runMapAction(() => {
    for (const event of ["init", "rotate", "rotation_changed", "idle"]) {
      listeners.push(maps.Event.addListener(map, event, scheduleReport));
    }
    reportRotation();
  });

  return {
    resetNorth() {
      runMapAction(() => {
        cancelReset();
        const startRotation = rotationFromNorth(map.getRotation());
        if (!isMapRotated(startRotation)) return;
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
          map.setRotation(0);
          reportRotation();
          return;
        }
        interruptEvents.forEach((event) =>
          element.addEventListener(event, cancelReset, { capture: true, passive: true }),
        );
        const startTime = performance.now();
        function rotate(now: number) {
          runMapAction(() => {
            const progress = Math.min(1, (now - startTime) / RESET_DURATION_MS);
            map.setRotation(progress < 1 ? startRotation * (1 - progress) ** 2 : 0);
            if (destroyed) return;
            reportRotation();
            if (progress < 1) resetFrame = window.requestAnimationFrame(rotate);
            else cancelReset();
          });
        }
        resetFrame = window.requestAnimationFrame(rotate);
      });
    },
    destroy,
  };
}
