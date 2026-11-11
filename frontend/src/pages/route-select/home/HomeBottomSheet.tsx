import {
  useCallback,
  useEffect,
  useId,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  type ReactNode,
  type RefObject,
} from "react";
import { useBoardSheet } from "@/shared/bottom-sheet/useBoardSheet";
import { useBoardViewport } from "@/shared/bottom-sheet/useBoardViewport";
import * as styles from "./HomeScreen.css";

export type HomeSheetSnap = "collapsed" | "summary" | "detail" | "full";
export type HomeSheetControls = { advance: () => void };

const BOARD_SNAP = { collapsed: "minimum", summary: "half", detail: "expanded", full: "full" } as const;
const HOME_SNAP = { minimum: "collapsed", half: "summary", expanded: "detail", full: "full" } as const;
const SLIDER_VALUE = { collapsed: 0, summary: 1, detail: 2, full: 3 };
const SLIDER_TEXT = { collapsed: "접힘", summary: "요약 표시", detail: "상세 표시", full: "전체 화면" };

export function HomeBottomSheet({
  children,
  controlsRef,
  surfaceRef,
  sectionRef,
  label,
  actionTitle,
  contentKey,
  station,
  home,
  showTabClose,
  snap,
  onSnapChange,
  onHeightChange,
}: {
  children: ReactNode;
  controlsRef: RefObject<HomeSheetControls | null>;
  surfaceRef: RefObject<HTMLDivElement | null>;
  sectionRef: RefObject<HTMLElement | null>;
  label: string | null;
  actionTitle?: ReactNode;
  contentKey: string;
  station: { id: string; name: string } | null;
  home: boolean;
  showTabClose: boolean;
  snap: HomeSheetSnap;
  onSnapChange: (snap: HomeSheetSnap) => void;
  onHeightChange: (height: number) => void;
}) {
  const bodyId = useId();
  const bodyRef = useRef<HTMLDivElement>(null);
  const stationHeadingRef = useRef<HTMLHeadingElement>(null);
  const closeAnimationRef = useRef<Animation | null>(null);
  const stationId = station?.id;
  const viewport = useBoardViewport();
  const available = label !== null;
  const collapsed = snap === "collapsed";
  const hidden = !available || (collapsed && station === null);
  const showClose = available && !collapsed && (station !== null || showTabClose);
  const homeOverview = home && station === null;
  const {
    sheetRef,
    headerRef,
    safeTopRef,
    safeBottomRef,
    height,
    dragging,
    dragHandlers,
    onHandleKeyDown,
    onHandleClick,
  } = useBoardSheet({
    snap: BOARD_SNAP[snap],
    onSnapChange: (next) => onSnapChange(HOME_SNAP[next]),
    viewportHeight: viewport.height,
    heightsFor: ({ viewportHeight, safeTop, safeBottom }) => {
      const full = Math.max(0, viewportHeight - safeTop - (station ? safeBottom : 0));
      const minimum = station ? Math.min(full, styles.handleHeight) : 0;
      const summaryHeight = station
        ? viewportHeight * 0.3
        : homeOverview
          ? viewportHeight * 0.38
          : viewportHeight * 0.46 + styles.navigationHeight + safeBottom;
      const summary = Math.min(full, Math.max(minimum, summaryHeight));
      const detailRatio = station ? styles.stopSheetViewportRatio : 0.7;
      const detail = Math.min(full, Math.max(summary, viewportHeight * detailRatio - (station ? safeBottom : 0)));
      return { minimum, half: summary, expanded: detail, full };
    },
  });
  const attachSheet = useCallback(
    (element: HTMLElement | null) => {
      sheetRef.current = element;
      sectionRef.current = element;
    },
    [sheetRef, sectionRef],
  );
  useImperativeHandle(controlsRef, () => ({ advance: onHandleClick }), [onHandleClick]);
  useLayoutEffect(
    () => () => {
      closeAnimationRef.current?.cancel();
      closeAnimationRef.current = null;
    },
    [contentKey, snap],
  );
  useEffect(() => {
    stationHeadingRef.current?.focus({ preventScroll: true });
  }, [stationId]);
  useEffect(() => {
    bodyRef.current?.scrollTo({ top: 0 });
  }, [contentKey]);
  useEffect(() => {
    onHeightChange(available ? height : 0);
  }, [available, height, onHeightChange]);

  function closeWithMotion() {
    if (closeAnimationRef.current) return;
    const surface = surfaceRef.current;
    if (!surface?.animate || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      onSnapChange("collapsed");
      return;
    }

    const animation = surface.animate(
      { transform: ["translateY(0)", "translateY(100%)"] },
      { duration: 280, easing: "cubic-bezier(.32, .72, .25, 1)", fill: "forwards" },
    );
    closeAnimationRef.current = animation;
    animation.onfinish = () => {
      if (closeAnimationRef.current === animation) onSnapChange("collapsed");
    };
  }

  return (
    <div
      ref={surfaceRef}
      className={styles.sheetSurface}
      data-full={snap === "full"}
      data-empty={hidden}
      data-tab-sheet={station === null}
    >
      <section
        id="home-bottom-sheet"
        ref={attachSheet}
        className={styles.sheet}
        style={{ height: available ? height : 0 }}
        data-snap={snap}
        data-dragging={dragging}
        aria-label={label ?? undefined}
        data-native-map-hit
        inert={hidden}
      >
        <span ref={safeTopRef} className={styles.safeTop} aria-hidden="true" />
        <span ref={safeBottomRef} className={styles.safeBottom} aria-hidden="true" />
        <div ref={headerRef} className={styles.sheetHeader} data-native-map-drag {...dragHandlers}>
          <div
            className={homeOverview ? styles.homeHandle : styles.handle}
            data-close-visible={showClose}
            role="slider"
            tabIndex={0}
            aria-label="홈 정보 시트 높이"
            aria-orientation="vertical"
            aria-valuemin={0}
            aria-valuemax={3}
            aria-valuenow={SLIDER_VALUE[snap]}
            aria-valuetext={SLIDER_TEXT[snap]}
            aria-controls={bodyId}
            onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onHandleClick();
              } else onHandleKeyDown(event);
            }}
            onClick={onHandleClick}
          >
            <span className={styles.handleBar} />
          </div>
          {showClose && (
            <div className={actionTitle ? styles.sheetActionsWithTitle : styles.sheetActions}>
              {actionTitle}
              <button
                type="button"
                className={styles.closeSheet}
                aria-label={station ? "정류장 안내 닫기" : `${label} 닫기`}
                onClick={(event) => {
                  if (!station) event.currentTarget.focus({ preventScroll: true });
                  closeWithMotion();
                }}
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="m4 4 8 8m0-8-8 8" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" />
                </svg>
              </button>
            </div>
          )}
          {station && (
            <h2 ref={stationHeadingRef} className={styles.stopHeading} tabIndex={-1}>
              {station.name}
            </h2>
          )}
        </div>
        <div
          id={bodyId}
          ref={bodyRef}
          className={station ? styles.sheetBody : homeOverview ? styles.homeSheetBody : styles.tabSheetBody}
          role="region"
          aria-label={station ? "정차 노선 목록" : "선택한 탭 내용"}
          hidden={collapsed && !dragging}
          inert={collapsed}
        >
          {children}
        </div>
      </section>
    </div>
  );
}
