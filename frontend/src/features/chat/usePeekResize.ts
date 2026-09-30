import { useLayoutEffect, useRef, type RefObject } from "react";
import { MOTION_MEDIA } from "./chatLayout";
import { RESIZE_DURATION_MS, RESIZING_ATTRIBUTE, resizeKeyframesFor } from "./peekMotion";

export function usePeekResize(
  launcherRef: RefObject<HTMLButtonElement | null>,
  labelRef: RefObject<HTMLSpanElement | null>,
  contentKey: string,
) {
  const settledWidth = useRef<number | null>(null);

  useLayoutEffect(() => {
    const launcher = launcherRef.current;
    const label = labelRef.current;
    if (launcher === null || label === null) return;

    const fromWidth = settledWidth.current;
    const toWidth = launcher.getBoundingClientRect().width;
    settledWidth.current = toWidth;
    if (fromWidth === null || Math.abs(toWidth - fromWidth) < 1 || !window.matchMedia(MOTION_MEDIA).matches) return;

    // 폭이 자라는 동안 글자가 다시 줄바꿈되거나 말줄임이 바뀌지 않게 최종 폭으로 고정한다
    label.style.flex = `0 0 ${label.getBoundingClientRect().width}px`;
    launcher.toggleAttribute(RESIZING_ATTRIBUTE, true);
    const animation = launcher.animate(resizeKeyframesFor(fromWidth, toWidth), { duration: RESIZE_DURATION_MS });
    const settle = () => {
      label.style.removeProperty("flex");
      launcher.toggleAttribute(RESIZING_ATTRIBUTE, false);
    };
    animation.addEventListener("finish", settle);

    return () => {
      if (animation.playState === "running") settledWidth.current = launcher.getBoundingClientRect().width;
      animation.cancel();
      settle();
    };
  }, [launcherRef, labelRef, contentKey]);
}
