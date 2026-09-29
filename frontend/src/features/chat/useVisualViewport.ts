import { useLayoutEffect, type RefObject } from "react";
import { SHEET_HEIGHT_VAR, VIEWPORT_HEIGHT_VAR, VIEWPORT_TOP_VAR } from "./chatLayout";
import { sheetHeight, type SheetSnap } from "./sheetPolicy";

export interface ViewportMetrics {
  height: number;
  offsetTop: number;
}

export function readViewport(): ViewportMetrics {
  const viewport = window.visualViewport ?? null;
  if (viewport === null) return { height: window.innerHeight, offsetTop: 0 };
  return { height: viewport.height, offsetTop: viewport.offsetTop };
}

export function useVisualViewport(targetRef: RefObject<HTMLElement | null>, snap: SheetSnap) {
  useLayoutEffect(() => {
    const target = targetRef.current;
    if (target === null) return;

    const update = () => {
      const { height, offsetTop } = readViewport();
      writeProperty(target, VIEWPORT_HEIGHT_VAR, `${height}px`);
      writeProperty(target, VIEWPORT_TOP_VAR, `${offsetTop}px`);
      writeProperty(target, SHEET_HEIGHT_VAR, `${sheetHeight(snap, height)}px`);
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
  }, [snap, targetRef]);
}

function writeProperty(target: HTMLElement, name: string, value: string) {
  if (target.style.getPropertyValue(name) !== value) target.style.setProperty(name, value);
}
