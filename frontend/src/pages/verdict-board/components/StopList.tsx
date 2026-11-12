import { useLayoutEffect, useRef, type RefObject } from "react";
import type { ExpandableStopView, StopView } from "./displayPolicy";
import type { LiveVehicleView } from "./liveVehiclePolicy";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import { LiveVehicleOverlay } from "./LiveVehicleOverlay";
import { StopRow } from "./StopRow";
import { HIDDEN_GUIDANCE, type ActionGuidanceView } from "./actionGuidance";
import type { StationArrivalDetails } from "./stationArrivalPolicy";
import type { RoutePosition } from "./TimelineMarker";
import type { StopSelection } from "./stopSelection";
import * as styles from "./StopList.css";
import type { FavoriteBoardRoute } from "./FavoriteButton";

interface StopListProps {
  favoriteRoute?: FavoriteBoardRoute;
  stops: StopView[];
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  entering: boolean;
  selectedSequence: number | null;
  scrollRequest: StopSelection | null;
  selectedVehicleKey: string | null;
  stationArrivals: StationArrivalDetails | null;
  actionGuidance?: ActionGuidanceView;
  onToggle: (sequence: number) => void;
  onVehicleSelect: (key: string) => void;
  scrollContainerRef: RefObject<HTMLElement | null>;
  onStopExpand: (stop: ExpandableStopView, rowPosition: number) => void;
}

const SCROLL_TARGET_TOP_PADDING = 12;
const STOP_SCROLL_DURATION_MS = 320;

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
  actionGuidance = HIDDEN_GUIDANCE,
  onToggle,
  onVehicleSelect,
  scrollContainerRef,
  onStopExpand,
  favoriteRoute,
}: StopListProps) {
  const routeCanvasRef = useRef<HTMLDivElement>(null);
  useStopScroll(routeCanvasRef, scrollContainerRef, scrollRequest);

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
            guidance={selectedSequence === stop.sequence ? actionGuidance : HIDDEN_GUIDANCE}
            onToggle={(expandableStop) => toggle(expandableStop, index)}
            {...(favoriteRoute ? { favoriteRoute } : {})}
          />
        ))}
      </ul>
      <LiveVehicleOverlay
        routeCanvasRef={routeCanvasRef}
        stops={stops}
        liveVehicleViews={liveVehicleViews}
        motion={motion}
        selectedVehicleKey={selectedVehicleKey}
        onVehicleSelect={onVehicleSelect}
      />
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
