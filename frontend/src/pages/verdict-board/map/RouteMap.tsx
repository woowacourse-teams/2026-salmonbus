import { MapLocationButton } from "@/shared/components/MapLocationButton";
import { useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useLocation } from "react-router";
import { MapFlowSurfaceContext } from "@/shared/map/mapFlowSurface";
import { boardEntryFrom } from "@/shared/routing/boardEntry";
import type { Board, Direction } from "@/shared/api/routeForecast.types";
import type { StopSelection } from "../components/stopSelection";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";
import type { RouteMapData } from "./routeMapModel";
import { loadNaverMaps, subscribeNaverMapAuthFailure } from "@/shared/map/naverSdk";
import { createNaverRouteMap, type RouteMapAdapter, type RouteMapCamera } from "./naverMapAdapter";
import type { RouteMapGeometryStatus } from "./useRouteMapData";
import { isNativeMapAvailable } from "@/shared/native-map/bridge";
import { createNativeRouteMap, type NativeRouteMapAdapter } from "./nativeMapAdapter";
import { reportNativeMapFailure } from "@/shared/error-tracking/errorTracking";
import { NOTICE_DURATION_MS } from "@/shared/components/Notice";
import { appHostFrom, isSupportedInApp, sendToApp } from "@/shared/bridge/bridge";
import * as styles from "./RouteMap.css";

interface RouteMapProps {
  board: Board;
  data: RouteMapData | null;
  geometryStatus: RouteMapGeometryStatus;
  geometryRetrying: boolean;
  selected: StopSelection | null;
  onSelect: (selection: StopSelection) => void;
  onVehicleSelect: (selection: StopSelection, key: string) => void;
  onDeselect: () => void;
  vehicleFocusRequest: { key: string; focusMarker: boolean } | null;
  selectedVehicleKey: string | null;
  liveVehicleViews: LiveVehicleView[];
  motion: LiveVehicleMotionStore;
  visible: boolean;
  viewportHeight: number;
  activeDirection: Direction;
  preserveViewOnEntry?: boolean;
  quietInitialLoad?: boolean;
}

const NO_VEHICLES: readonly LiveVehicleView[] = [];

type MapState =
  | { status: "loading" }
  | { status: "ready"; data: RouteMapData }
  | { status: "error"; data: RouteMapData; message: string };
type LocationState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "message"; message: string }
  | { status: "settingsPrompt"; message: string };

// 설정 버튼이 있는 안내는 화면 읽기 사용자가 버튼까지 갈 시간이 필요해서 시간 제한 없이 닫기 버튼으로 닫는다.
function locationDeniedStateFor(message: string): LocationState {
  return isSupportedInApp("settings.open") ? { status: "settingsPrompt", message } : { status: "message", message };
}

export function RouteMap(props: RouteMapProps) {
  const refitOverviewOnResize = boardEntryFrom(useLocation().state) === "search";
  const mapFlow = useContext(MapFlowSurfaceContext);
  const sharedSurface = mapFlow?.surface ?? null;
  const shared = mapFlow !== null;
  const {
    data,
    geometryStatus,
    geometryRetrying,
    visible,
    viewportHeight,
    selected,
    activeDirection,
    liveVehicleViews,
    motion,
    vehicleFocusRequest,
    selectedVehicleKey,
    preserveViewOnEntry = false,
    quietInitialLoad = false,
  } = props;
  const selectedStopOnly = geometryStatus === "generating" || geometryStatus === "withheld";
  const regionRef = useRef<HTMLElement | null>(null);
  const entryCropApplied = useRef(false);
  const previousView = useRef<{
    routeId: string;
    referenceVersionId: string;
    camera?: RouteMapCamera;
    native: boolean;
  } | null>(null);
  const elementRef = useRef<HTMLDivElement>(null);
  const adapterRef = useRef<RouteMapAdapter | NativeRouteMapAdapter | null>(null);
  const lastFocusedStop = useRef<StopSelection | null>(null);
  const lastVehicleFocusRequest = useRef<RouteMapProps["vehicleFocusRequest"]>(null);
  const propsRef = useRef(props);
  const locationRequest = useRef(0);
  const [mapState, setMapState] = useState<MapState>({ status: "loading" });
  const [locationState, setLocationState] = useState<LocationState>({ status: "idle" });
  const [nativeFailed, setNativeFailed] = useState(false);
  const useNativeMap =
    !nativeFailed &&
    isNativeMapAvailable() &&
    (!selectedStopOnly || appHostFrom()?.capabilities.includes("map.selectedStopOnly") === true);
  const webSurface = useNativeMap ? null : sharedSurface;

  useLayoutEffect(() => {
    propsRef.current = props;
  }, [props]);

  const disposeMap = useCallback(() => {
    const adapter = adapterRef.current;
    adapterRef.current = null;
    lastVehicleFocusRequest.current = null;
    lastFocusedStop.current = null;
    locationRequest.current += 1;
    adapter?.destroy();
    elementRef.current?.replaceChildren();
  }, []);

  const failMap = useCallback(
    (failedData: RouteMapData) => {
      if (propsRef.current.data !== failedData) return;
      disposeMap();
      setMapState({
        status: "error",
        data: failedData,
        message: "지도를 불러오지 못했어요",
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
      } catch {
        const currentData = propsRef.current.data;
        if (currentData !== null) failMap(currentData);
      }
    },
    [failMap],
  );

  useEffect(() => {
    if (data === null) {
      previousView.current = null;
      return;
    }
    if (shared && !useNativeMap && webSurface === null) return;
    const { routeId, referenceVersionId } = data;
    const view = previousView.current;
    const sameVersion = view?.routeId === data.routeId && view.referenceVersionId === data.referenceVersionId;
    const webCamera = sameVersion ? view.camera : undefined;
    const preserveNativeCamera = sameVersion && view.native;
    let ready = false;
    function rememberCamera(adapter: RouteMapAdapter, native: boolean) {
      if (!ready) return;
      try {
        const camera = adapter.camera?.();
        previousView.current = {
          routeId,
          referenceVersionId,
          native,
          ...(camera && { camera }),
        };
      } catch {
        // SDK가 이미 지도를 해제한 경우에는 위치 복원 없이 정리한다.
        previousView.current = null;
      }
    }
    let active = true;
    let failed = false;
    const failActiveMap = () => {
      if (!active) return;
      failed = true;
      failMap(data);
    };
    if (useNativeMap) {
      const region = regionRef.current;
      if (region !== null) {
        try {
          const current = propsRef.current;
          const adapter = createNativeRouteMap({
            region,
            board: current.board,
            data,
            motion: current.motion,
            initialSelection: current.selected,
            preserveCamera: preserveNativeCamera,
            selectedStopOnly,
            onSelect: (selection) => propsRef.current.onSelect(selection),
            onVehicleSelect: (selection, key) => propsRef.current.onVehicleSelect(selection, key),
            onDeselect: () => propsRef.current.onDeselect(),
            onError: (_error, reason) => {
              if (!active || failed || propsRef.current.data !== data) return;
              if (reason === undefined) {
                failActiveMap();
                return;
              }
              failed = true;
              disposeMap();
              setMapState({ status: "loading" });
              setLocationState({ status: "idle" });
              setNativeFailed(true);
              reportNativeMapFailure(reason, data.routeId);
            },
            onReady: () => {
              if (!active || failed || propsRef.current.data !== data) return;
              // 이전 앱도 초기 정류장을 확대할 수 있도록 준비 후 카메라 명령은 유지한다.
              const selection = propsRef.current.selected;
              if (!preserveNativeCamera && selection !== null) adapterRef.current?.focus(selection);
              ready = true;
              lastFocusedStop.current = selection;
              setMapState({ status: "ready", data });
              setLocationState({ status: "idle" });
            },
          });
          adapterRef.current = adapter;
          lastFocusedStop.current = current.selected;
          adapter.update(
            current.selected,
            current.activeDirection,
            selectedStopOnly ? NO_VEHICLES : current.liveVehicleViews,
            current.selectedVehicleKey,
          );
        } catch {
          failActiveMap();
        }
      }
      return () => {
        active = false;
        if (adapterRef.current !== null) rememberCamera(adapterRef.current, true);
        disposeMap();
      };
    }
    const unsubscribeAuthFailure = subscribeNaverMapAuthFailure(() => failActiveMap());
    void (webSurface ? Promise.resolve(webSurface.maps) : loadNaverMaps(__NAVER_MAP_KEY_ID__))
      .then((sdk) => {
        const element = webSurface?.element ?? elementRef.current;
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
          () => failActiveMap(),
          () => {
            if (!active || failed || propsRef.current.data !== data) return;
            const selection = propsRef.current.selected;
            if (webCamera === undefined && selection !== null) adapterRef.current?.focus(selection, { animate: true });
            ready = true;
            lastFocusedStop.current = selection;
            setMapState({ status: "ready", data });
            setLocationState({ status: "idle" });
          },
          (selection, key) => propsRef.current.onVehicleSelect(selection, key),
          () => propsRef.current.onDeselect(),
          current.selected,
          webCamera,
          selectedStopOnly,
          { ...(webSurface && { sharedMap: webSurface.map }), refitOverviewOnResize },
        );
        adapterRef.current = adapter;
        lastFocusedStop.current = current.selected;
        adapter.update(
          current.selected,
          current.activeDirection,
          selectedStopOnly ? NO_VEHICLES : current.liveVehicleViews,
          current.selectedVehicleKey,
        );
        if (!selectedStopOnly) adapter.moveVehicles(current.motion.frames);
        if (failed) {
          disposeMap();
          return;
        }
      })
      .catch(failActiveMap);
    return () => {
      active = false;
      unsubscribeAuthFailure();
      if (adapterRef.current !== null) rememberCamera(adapterRef.current, false);
      disposeMap();
    };
  }, [data, disposeMap, failMap, refitOverviewOnResize, selectedStopOnly, shared, webSurface, useNativeMap]);

  useEffect(() => {
    runMapAction((adapter) => {
      adapter.update(selected, activeDirection, selectedStopOnly ? NO_VEHICLES : liveVehicleViews, selectedVehicleKey);
      if (!selectedStopOnly) adapter.moveVehicles(motion.frames);
    });
  }, [
    selected,
    activeDirection,
    liveVehicleViews,
    motion,
    selectedVehicleKey,
    mapState,
    runMapAction,
    selectedStopOnly,
  ]);

  useEffect(() => {
    if (mapState.status !== "ready" || mapState.data !== data) return;
    const previous = lastFocusedStop.current;
    lastFocusedStop.current = selected;
    if (selected !== null && (selected.direction !== previous?.direction || selected.sequence !== previous?.sequence))
      runMapAction((adapter) => adapter.focus(selected));
  }, [selected, mapState, data, runMapAction]);

  useEffect(() => {
    if (mapState.status !== "ready" || !visible || selectedStopOnly) {
      runMapAction((adapter) => adapter.focusVehicle(null));
      return;
    }
    // 시트 높이가 바뀌어 지도가 다시 보여도 처리한 이동 요청을 반복하지 않는다.
    if (lastVehicleFocusRequest.current === vehicleFocusRequest) return;
    lastVehicleFocusRequest.current = vehicleFocusRequest;
    runMapAction((adapter) =>
      adapter.focusVehicle(vehicleFocusRequest?.key ?? null, { focusMarker: vehicleFocusRequest?.focusMarker ?? true }),
    );
  }, [vehicleFocusRequest, mapState, visible, runMapAction, selectedStopOnly]);

  useEffect(() => {
    if (useNativeMap || selectedStopOnly) return;
    return motion.subscribe(() => runMapAction((adapter) => adapter.moveVehicles(motion.frames)));
  }, [motion, runMapAction, selectedStopOnly, useNativeMap]);

  useEffect(() => {
    if (locationState.status !== "message") return;
    const timer = window.setTimeout(() => setLocationState({ status: "idle" }), NOTICE_DURATION_MS);
    return () => window.clearTimeout(timer);
  }, [locationState]);

  useEffect(() => {
    const region = regionRef.current;
    if (region === null) return;
    // 시트는 지도를 잘라 보이는 높이만 바꾼다. GL 버퍼 크기는 화면 크기가 바뀔 때만 갱신한다.
    const observer = new ResizeObserver(() => {
      const visibleHeight = region.clientHeight;
      const crop = Math.max(0, (viewportHeight - visibleHeight) / 2);
      const cropInset = `${crop}px`;
      region.style.setProperty("--map-crop-inset", cropInset);
      if (webSurface && data && window.naver?.maps === webSurface.maps) {
        if (preserveViewOnEntry && visible && !entryCropApplied.current) {
          entryCropApplied.current = true;
          const { map, maps } = webSurface;
          const projection = map.getProjection();
          const center = projection.fromCoordToOffset(map.getCenter());
          // 캔버스가 위로 잘리는 만큼 중심을 옮겨 처음 보이던 지도 위치를 유지한다.
          map.setCenter(projection.fromOffsetToCoord(new maps.Point(center.x, center.y - crop)));
        }
        webSurface.element.style.setProperty("--board-map-crop-inset", cropInset);
      }
      runMapAction((adapter) => adapter.resize(region.clientWidth, viewportHeight, visibleHeight));
    });
    observer.observe(region);
    return () => observer.disconnect();
  }, [data, mapState, preserveViewOnEntry, webSurface, viewportHeight, visible, runMapAction]);

  useEffect(
    () => () => {
      webSurface?.element.style.removeProperty("--board-map-crop-inset");
    },
    [webSurface],
  );

  useEffect(() => {
    if (!sharedSurface || visible) return;
    const { element } = sharedSurface;
    element.setAttribute("inert", "");
    return () => {
      element.removeAttribute("inert");
    };
  }, [sharedSurface, visible]);

  function locate() {
    const adapter = adapterRef.current;
    if (adapter === null) return;
    if ("native" in adapter) {
      const request = ++locationRequest.current;
      setLocationState({ status: "loading" });
      void adapter
        .locate(request)
        .then((coordinate) => {
          if (request !== locationRequest.current || adapterRef.current !== adapter) return;
          runMapAction((current) => current.showLocation(coordinate));
          setLocationState({ status: "idle" });
        })
        .catch((error: unknown) => {
          if (request !== locationRequest.current || adapterRef.current !== adapter) return;
          const reason = error instanceof Error ? error.message : "unavailable";
          setLocationState(
            reason === "denied"
              ? locationDeniedStateFor("iPhone 설정에서 연어버스의 위치 권한을 허용해주세요")
              : {
                  status: "message",
                  message:
                    reason === "timeout"
                      ? "위치를 확인하는 데 시간이 걸려요. 다시 눌러주세요"
                      : "현재 위치를 확인하지 못했어요. 다시 눌러주세요",
                },
          );
        });
      return;
    }
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
        setLocationState(
          error.code === 1
            ? locationDeniedStateFor("위치 권한을 허용하면 내 위치를 볼 수 있어요")
            : {
                status: "message",
                message:
                  error.code === 3
                    ? "위치를 확인하는 데 시간이 걸려요. 다시 눌러주세요"
                    : "현재 위치를 확인하지 못했어요. 다시 눌러주세요",
              },
        );
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 },
    );
  }

  function openSettings() {
    sendToApp("settings.open", {});
    setLocationState({ status: "idle" });
  }

  const currentMapState =
    mapState.status !== "loading" && mapState.data === data ? mapState : { status: "loading" as const };
  const preparing = geometryStatus === "preparing" || geometryStatus === "generating";
  const quietPreparation = quietInitialLoad && (geometryStatus === "loading" || preparing);
  const withheld = geometryStatus === "withheld";
  const geometryNotice = preparing ? "노선을 준비 중이에요" : withheld ? "현재 노선을 표시할 수 없어요" : null;
  const stopInfoNotice = withheld ? "정류장 정보는 확인 가능해요" : "정류장 목록은 계속 확인할 수 있어요";
  const sharedMapFailed = !useNativeMap && mapFlow?.status === "error";
  const unavailableMessage =
    currentMapState.status === "error"
      ? currentMapState.message
      : sharedMapFailed
        ? "지도를 불러오지 못했어요"
        : data === null && !quietPreparation
          ? (geometryNotice ??
            (geometryStatus === "error" || geometryStatus === "retrying"
              ? "지도를 불러오지 못했어요"
              : "지도 정보를 준비 중이에요"))
          : null;
  const ready = currentMapState.status === "ready";
  const showSharedNativeMap = shared && useNativeMap && ready;
  useLayoutEffect(() => {
    if (!showSharedNativeMap) return;
    const root = document.documentElement;
    root.dataset.sharedNativeMapReady = "true";
    return () => {
      delete root.dataset.sharedNativeMapReady;
    };
  }, [showSharedNativeMap]);
  return (
    <section
      ref={regionRef}
      className={shared ? styles.sharedRegion : styles.region}
      style={{ pointerEvents: visible && !shared ? "auto" : "none" }}
      aria-label="노선 지도"
      aria-busy={
        !sharedMapFailed &&
        currentMapState.status === "loading" &&
        geometryStatus !== "error" &&
        geometryStatus !== "retrying"
      }
      aria-hidden={!visible}
      inert={!visible}
    >
      {!shared && (
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
      )}
      {unavailableMessage !== null ? (
        <div className={shared ? styles.sharedNotice : styles.notice} role="status" data-native-map-hit>
          <span>{unavailableMessage}</span>
          {geometryStatus === "retrying" && <span>잠시 후 자동으로 다시 불러와요.</span>}
          <span>{stopInfoNotice}</span>
        </div>
      ) : null}
      {data !== null &&
      ready &&
      (geometryRetrying ||
        (!quietPreparation &&
          (geometryNotice !== null || !data.paths.some((path) => path.direction === activeDirection)))) ? (
        <div
          className={
            withheld ? styles.withheldToast : geometryNotice === null ? styles.pathNotice : styles.geometryToast
          }
          role="status"
        >
          {geometryRetrying
            ? "지도를 갱신하지 못했어요. 잠시 후 자동으로 다시 불러와요."
            : (geometryNotice ?? "방향별 경로 정보가 없어 버스는 최근 확인된 정류장에 표시해요")}
          {withheld && (
            <>
              <br />
              {stopInfoNotice}
            </>
          )}
        </div>
      ) : null}
      {data !== null && ready ? (
        <MapLocationButton
          className={styles.location}
          aria-label="현재 위치 보기"
          onClick={locate}
          data-native-map-hit
          disabled={locationState.status === "loading"}
        />
      ) : null}
      {ready && locationState.status === "message" ? (
        <div className={styles.locationNotice} role="status" data-native-map-hit>
          {locationState.message}
        </div>
      ) : null}
      {ready && locationState.status === "settingsPrompt" ? (
        <div className={styles.locationNotice} data-native-map-hit>
          <p className={styles.locationPromptText} role="status">
            {locationState.message}
          </p>
          <div className={styles.locationPromptActions}>
            <button
              type="button"
              className={styles.locationDismissButton}
              onClick={() => setLocationState({ status: "idle" })}
            >
              닫기
            </button>
            <button type="button" className={styles.locationSettingsButton} onClick={openSettings}>
              설정 열기
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
