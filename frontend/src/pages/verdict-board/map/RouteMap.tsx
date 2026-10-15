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
  vehicleFocusRequest: { key: string; focusMarker: boolean } | null;
  selectedVehicleKey: string | null;
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  visible: boolean;
  viewportHeight: number;
  activeDirection: Direction;
}

type MapState =
  | { status: "loading" }
  | { status: "ready"; data: RouteMapData }
  | { status: "error"; data: RouteMapData; message: string };
type LocationState = { status: "idle" } | { status: "loading" } | { status: "message"; message: string };

export function RouteMap(props: RouteMapProps) {
  const {
    data,
    visible,
    viewportHeight,
    selected,
    activeDirection,
    liveVehicleViews,
    motion,
    vehicleFocusRequest,
    selectedVehicleKey,
  } = props;
  const regionRef = useRef<HTMLElement | null>(null);
  const elementRef = useRef<HTMLDivElement>(null);
  const adapterRef = useRef<RouteMapAdapter | null>(null);
  const lastVehicleFocusRequest = useRef<RouteMapProps["vehicleFocusRequest"]>(null);
  const propsRef = useRef(props);
  const locationRequest = useRef(0);
  const [mapState, setMapState] = useState<MapState>({ status: "loading" });
  const [locationState, setLocationState] = useState<LocationState>({ status: "idle" });

  useLayoutEffect(() => {
    propsRef.current = props;
  }, [props]);

  const disposeMap = useCallback(() => {
    const adapter = adapterRef.current;
    adapterRef.current = null;
    lastVehicleFocusRequest.current = null;
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
        const region = regionRef.current;
        if (!active || failed || element === null || region === null) return;
        const current = propsRef.current;
        const adapter = createNaverRouteMap(
          sdk,
          element,
          current.board,
          data,
          region.clientHeight,
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
        adapter.update(current.selected, current.activeDirection, current.liveVehicleViews, current.selectedVehicleKey);
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
      adapter.update(selected, activeDirection, liveVehicleViews, selectedVehicleKey);
      adapter.moveVehicles(motion.frames);
    });
  }, [selected, activeDirection, liveVehicleViews, motion, selectedVehicleKey, mapState, runMapAction]);

  useEffect(() => {
    if (selected !== null) runMapAction((adapter) => adapter.focus(selected));
  }, [selected, runMapAction]);

  useEffect(() => {
    if (mapState.status !== "ready" || !visible) {
      runMapAction((adapter) => adapter.focusVehicle(null));
      return;
    }
    // 시트 높이가 바뀌어 지도가 다시 보여도 처리한 이동 요청을 반복하지 않는다.
    if (lastVehicleFocusRequest.current === vehicleFocusRequest) return;
    lastVehicleFocusRequest.current = vehicleFocusRequest;
    runMapAction((adapter) =>
      adapter.focusVehicle(vehicleFocusRequest?.key ?? null, { focusMarker: vehicleFocusRequest?.focusMarker ?? true }),
    );
  }, [vehicleFocusRequest, mapState, visible, runMapAction]);

  useEffect(
    () => motion.subscribe(() => runMapAction((adapter) => adapter.moveVehicles(motion.frames))),
    [motion, runMapAction],
  );

  useEffect(() => {
    const region = regionRef.current;
    if (region === null) return;
    // 시트는 지도를 잘라 보이는 높이만 바꾼다. GL 버퍼 크기는 화면 크기가 바뀔 때만 갱신한다.
    const observer = new ResizeObserver(() => {
      region.style.setProperty("--map-crop-inset", `${Math.max(0, (viewportHeight - region.clientHeight) / 2)}px`);
      runMapAction((adapter) => adapter.resize(region.clientWidth, viewportHeight));
    });
    observer.observe(region);
    return () => observer.disconnect();
  }, [mapState, viewportHeight, runMapAction]);

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
          adapter.showLocation({ latitude: position.coords.latitude, longitude: position.coords.longitude }),
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
      style={{ pointerEvents: visible ? "auto" : "none" }}
      aria-label="노선 지도"
      aria-busy={currentMapState.status === "loading"}
      aria-hidden={!visible}
      inert={!visible}
    >
      <div className={styles.canvasViewport} style={{ height: viewportHeight }}>
        <div
          ref={elementRef}
          className={styles.canvas}
          data-testid="route-map"
          data-overlays-ready={ready}
          inert={!ready}
          aria-hidden={!ready}
        />
      </div>
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
