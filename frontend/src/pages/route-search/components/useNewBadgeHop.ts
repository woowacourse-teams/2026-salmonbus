import { useEffect, useRef, type RefObject } from "react";
import { hoppingBadgeIndexFor } from "../new-route/newBadgeHopPolicy";

// 입력이 멈췄다고 보는 시간. route_search_unmatched가 기다리는 1초와 같은 값에서 시작한다.
const HOP_DELAY_MS = 1000;

const NEW_BADGE_SELECTOR = "[data-new-badge]";

// 연어버스 로고 진입 모션(SAL-145)과 같은 스프링(감쇠비 0.595, 응답 617ms)으로 8px를 한 번 튀게 계산한 값
const HOP_KEYFRAMES: Keyframe[] = [
  { offset: 0, translate: "0 0" },
  { offset: 0.017, translate: "0 -1.83px" },
  { offset: 0.033, translate: "0 -3.38px" },
  { offset: 0.05, translate: "0 -4.67px" },
  { offset: 0.067, translate: "0 -5.72px" },
  { offset: 0.083, translate: "0 -6.55px" },
  { offset: 0.1, translate: "0 -7.17px" },
  { offset: 0.117, translate: "0 -7.6px" },
  { offset: 0.15, translate: "0 -7.99px" },
  { offset: 0.183, translate: "0 -7.86px" },
  { offset: 0.233, translate: "0 -6.99px" },
  { offset: 0.333, translate: "0 -4.19px" },
  { offset: 0.417, translate: "0 -1.95px" },
  { offset: 0.483, translate: "0 -0.62px" },
  { offset: 0.55, translate: "0 0.23px" },
  { offset: 0.633, translate: "0 0.72px" },
  { offset: 0.75, translate: "0 0.71px" },
  { offset: 1, translate: "0 0" },
];
const HOP_TIMING: KeyframeAnimationOptions = { duration: 727, easing: "linear" };

export function useNewBadgeHop(containerRef: RefObject<HTMLElement | null>, hopKey: string | null) {
  const hoppedKeys = useRef(new Set<string>());

  useEffect(() => {
    if (hopKey === null || hoppedKeys.current.has(hopKey)) return;
    const timer = window.setTimeout(() => {
      hoppedKeys.current.add(hopKey);
      const container = containerRef.current;
      if (container === null || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
      const badges = [...container.querySelectorAll<HTMLElement>(NEW_BADGE_SELECTOR)];
      const index = hoppingBadgeIndexFor(
        badges.map((badge) => badge.getBoundingClientRect()),
        visibleAreaOf(container),
      );
      if (index !== null) badges[index]?.animate(HOP_KEYFRAMES, HOP_TIMING);
    }, HOP_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [containerRef, hopKey]);
}

// 아이폰은 키패드가 올라오면 visualViewport만 줄어든다. 키패드에 가린 배지는 보이는 것으로 치지 않는다.
function visibleAreaOf(container: HTMLElement) {
  const { top, bottom } = container.getBoundingClientRect();
  const viewportTop = window.visualViewport?.offsetTop ?? 0;
  const viewportBottom = viewportTop + (window.visualViewport?.height ?? window.innerHeight);
  return { top: Math.max(top, viewportTop), bottom: Math.min(bottom, viewportBottom) };
}
