import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Board, Direction } from "@/shared/api/routeForecast.types";
import type { StopSelection } from "../components/stopSelection";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";
import type { RouteMapData } from "./routeMapModel";
import { loadNaverMaps, subscribeNaverMapAuthFailure } from "./naverSdk";
import { createNaverRouteMap, type RouteMapAdapter } from "./naverMapAdapter";
import * as styles from "./RouteMap.css";

interface RouteMapProps {
  board: Board;
  data: RouteMapData | null;
  selected: StopSelection | null;
  onSelect: (selection: StopSelection) => void;
  onVehicleSelect: (selection: StopSelection, key: string) => void;
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  viewportHeight: number;
  activeDirection: Direction;
}

type MapState =
  | { status: "loading" }
  | { status: "ready"; data: RouteMapData }
  | { status: "error"; data: RouteMapData; message: string };
type LocationState = { status: "idle" } | { status: "loading" } | { status: "message"; message: string };

export function RouteMap(props: RouteMapProps) {
  const { data, viewportHeight, selected, activeDirection, liveVehicleViews, motion } = props;
  const regionRef = useRef<HTMLElement | null>(null);
  const elementRef = useRef<HTMLDivElement>(null);
  const adapterRef = useRef<RouteMapAdapter | null>(null);
  const propsRef = useRef(props);
  const locationRequest = useRef(0);
  const [mapState, setMapState] = useState<MapState>({ status: "loading" });
  const [locationState, setLocationState] = useState<LocationState>({ status: "idle" });
  const [height, setHeight] = useState(Math.max(1, viewportHeight));
  if (viewportHeight > 0 && height !== viewportHeight) setHeight(viewportHeight);

  useLayoutEffect(() => {
    propsRef.current = props;
  }, [props, viewportHeight]);

  const disposeMap = useCallback(() => {
    const adapter = adapterRef.current;
    adapterRef.current = null;
    locationRequest.current += 1;
    adapter?.destroy();
    elementRef.current?.replaceChildren();
  }, []);

  const failMap = useCallback(
    (failedData: RouteMapData, error: unknown) => {
      if (propsRef.current.data !== failedData) return;
      disposeMap();
      setMapState({
        status: "error",
        data: failedData,
        message:
          error instanceof Error && error.message === "지도 설정을 준비 중이에요"
            ? error.message
            : "지도를 불러오지 못했어요",
      });
      setLocationState({ status: "idle" });
    },
    [disposeMap],
  );

  const runMapAction = useCallback(
    (action: (adapter: RouteMapAdapter) => void) => {
      const adapter = adapterRef.current;
      if (adapter === null) return;
      try {
        action(adapter);
      } catch (error) {
        const currentData = propsRef.current.data;
        if (currentData !== null) failMap(currentData, error);
      }
    },
    [failMap],
  );

  useEffect(() => {
    if (data === null) return;
    let active = true;
    let failed = false;
    const failActiveMap = (error: unknown) => {
      if (!active) return;
      failed = true;
      failMap(data, error);
    };
    const unsubscribeAuthFailure = subscribeNaverMapAuthFailure(() => failActiveMap(null));
    void loadNaverMaps(__NAVER_MAP_KEY_ID__)
      .then((sdk) => {
        const element = elementRef.current;
        if (!active || failed || element === null) return;
        const current = propsRef.current;
        const adapter = createNaverRouteMap(
          sdk,
          element,
          current.board,
          data,
          (selection) => propsRef.current.onSelect(selection),
          () => failActiveMap(null),
          () => {
            if (!active || failed || propsRef.current.data !== data) return;
            setMapState({ status: "ready", data });
            setLocationState({ status: "idle" });
          },
          (selection, key) => propsRef.current.onVehicleSelect(selection, key),
        );
        adapterRef.current = adapter;
        adapter.update(current.selected, current.activeDirection, current.liveVehicleViews);
        adapter.moveVehicles(current.motion.frames);
        if (current.selected !== null) adapter.focus(current.selected);
        if (failed) {
          disposeMap();
          return;
        }
      })
      .catch((error: unknown) => {
        failActiveMap(error);
      });
    return () => {
      active = false;
      unsubscribeAuthFailure();
      disposeMap();
    };
  }, [data, disposeMap, failMap]);

  useEffect(() => {
    runMapAction((adapter) => {
      adapter.update(selected, activeDirection, liveVehicleViews);
      adapter.moveVehicles(motion.frames);
    });
  }, [selected, activeDirection, liveVehicleViews, motion, mapState, runMapAction]);

  useEffect(() => {
    if (selected !== null) runMapAction((adapter) => adapter.focus(selected));
  }, [selected, runMapAction]);

  useEffect(
    () => motion.subscribe(() => runMapAction((adapter) => adapter.moveVehicles(motion.frames))),
    [motion, runMapAction],
  );

  useLayoutEffect(() => {
    // SDK가 지도 요소의 크기를 픽셀 값으로 고정하므로 바깥 영역을 측정한다.
    const region = regionRef.current;
    if (region === null || viewportHeight <= 0) return;
    runMapAction((adapter) => adapter.resize(region.clientWidth, region.clientHeight));
  }, [height, viewportHeight, mapState, runMapAction]);

  useEffect(() => {
    const region = regionRef.current;
    if (region === null) return;
    const observer = new ResizeObserver(() => {
      if (propsRef.current.viewportHeight > 0)
        runMapAction((adapter) => adapter.resize(region.clientWidth, region.clientHeight));
    });
    observer.observe(region);
    return () => observer.disconnect();
  }, [runMapAction]);

  function locate() {
    if (adapterRef.current === null) return;
    if (!navigator.geolocation) {
      setLocationState({ status: "message", message: "현재 위치를 지원하지 않는 환경이에요" });
      return;
    }
    const request = ++locationRequest.current;
    setLocationState({ status: "loading" });
    navigator.geolocation.getCurrentPosition(
      (position) => {
        if (request !== locationRequest.current || adapterRef.current === null) return;
        runMapAction((adapter) =>
          adapter.showLocation(
            { latitude: position.coords.latitude, longitude: position.coords.longitude },
            position.coords.accuracy,
          ),
        );
        setLocationState({ status: "idle" });
      },
      (error) => {
        if (request !== locationRequest.current) return;
        setLocationState({
          status: "message",
          message:
            error.code === 1
              ? "위치 권한을 허용하면 내 위치를 볼 수 있어요"
              : error.code === 3
                ? "위치를 확인하는 데 시간이 걸려요. 다시 눌러주세요"
                : "현재 위치를 확인하지 못했어요. 다시 눌러주세요",
        });
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 },
    );
  }

  const currentMapState =
    mapState.status !== "loading" && mapState.data === data ? mapState : { status: "loading" as const };
  const unavailableMessage =
    data === null ? "지도 정보를 준비 중이에요" : currentMapState.status === "error" ? currentMapState.message : null;
  const ready = currentMapState.status === "ready";
  return (
    <section
      ref={regionRef}
      className={styles.region}
      style={{ height, pointerEvents: viewportHeight <= 0 ? "none" : "auto" }}
      aria-label="노선 지도"
      aria-busy={currentMapState.status === "loading"}
      aria-hidden={viewportHeight <= 0}
      inert={viewportHeight <= 0}
    >
      <div
        ref={elementRef}
        className={styles.canvas}
        data-testid="route-map"
        data-overlays-ready={ready}
        inert={!ready}
        aria-hidden={!ready}
      />
      {unavailableMessage !== null ? (
        <div className={styles.notice} role="status">
          <span>{unavailableMessage}</span>
          <span>정류장 목록은 계속 확인할 수 있어요</span>
        </div>
      ) : null}
      {data !== null && ready ? (
        <button
          type="button"
          className={styles.location}
          aria-label="현재 위치 보기"
          onClick={locate}
          disabled={locationState.status === "loading"}
        >
          <svg
            width="22"
            height="22"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="6" />
            <circle cx="12" cy="12" r="2" />
            <path d="M12 2v3m0 14v3M2 12h3m14 0h3" />
          </svg>
        </button>
      ) : null}
      {ready && locationState.status === "message" ? (
        <div className={styles.locationNotice} role="status">
          {locationState.message}
        </div>
      ) : null}
    </section>
  );
}
