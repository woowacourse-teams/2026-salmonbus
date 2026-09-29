import { useLayoutEffect, type RefObject } from "react";
import { HALF_OFFSET_VAR, VIEWPORT_HEIGHT_VAR, VIEWPORT_TOP_VAR } from "./chatLayout";
import { sheetOffset } from "./sheetPolicy";

export interface ViewportMetrics {
  height: number;
  offsetTop: number;
}

export function readViewport(): ViewportMetrics {
  const viewport = window.visualViewport ?? null;
  if (viewport === null) return { height: window.innerHeight, offsetTop: 0 };
  return { height: viewport.height, offsetTop: viewport.offsetTop };
}

export function useVisualViewport(targetRef: RefObject<HTMLElement | null>) {
  useLayoutEffect(() => {
    const target = targetRef.current;
    if (target === null) return;

    const update = () => {
      const { height, offsetTop } = readViewport();
      target.style.setProperty(VIEWPORT_HEIGHT_VAR, `${height}px`);
      target.style.setProperty(VIEWPORT_TOP_VAR, `${offsetTop}px`);
      target.style.setProperty(HALF_OFFSET_VAR, `${sheetOffset("half", height)}px`);
    };

    update();
    const viewport = window.visualViewport ?? null;
    if (viewport === null) {
      window.addEventListener("resize", update);
      return () => window.removeEventListener("resize", update);
    }
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, [targetRef]);
}
