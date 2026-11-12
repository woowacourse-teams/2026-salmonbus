import { MapLocationButton } from "@/shared/components/MapLocationButton";
import { MapCompassButton } from "@/shared/components/MapCompassButton";
import type { Direction } from "@/shared/api/routeForecast.types";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from "react";
import { loadNaverMaps, subscribeNaverMapAuthFailure, type NaverCoordinate } from "@/shared/map/naverSdk";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import type { MapFlowSurface, MapFlowSurfaceState } from "@/shared/map/mapFlowSurface";
import { isMapRotated } from "@/shared/map/mapRotation";
import { bindNaverMapCompass } from "@/shared/map/naverMapCompass";
import { mountHomeStops } from "./homeStopMarkers";
import { HomeRouteNetwork } from "./HomeRouteNetwork";
import { FavoriteMapRoute } from "./FavoriteMapRoute";
import { homeStationTileKey, MIN_STATION_ZOOM } from "./homeStationTiles";
import { distanceMeters, type HomeStop, type Point } from "./homeMapData";
import * as styles from "./HomeScreen.css";

interface Props {
  point: Point;
  location: Point | null;
  stops: HomeStop[];
  routePaths: Point[][];
  routeOverviewKey: string | null;
  favoriteGroupId: string | null;
  favoriteRoutes: ReadonlyMap<string, Direction>;
  overlayOpen: boolean;
  boardOpen: boolean;
  onSurfaceChange: (state: MapFlowSurfaceState) => void;
  sheetSurfaceRef: RefObject<HTMLDivElement | null>;
  selectedStop: HomeStop | undefined;
  searchBarRef: RefObject<HTMLElement | null>;
  onCenterChange: (point: Point, stopFocus: boolean) => void;
  onStationTilesChange: (key: string) => void;
  onSelectStop: (id: string) => void;
  onMapBlankClick: (() => void) | null;
  onLocate: () => void;
  locating: boolean;
}

export function HomeMap(props: Props) {
  const element = useRef<HTMLDivElement>(null);
  const latest = useRef(props);
  const pendingStopCenter = useRef<Point | null>(null);
  const overviewReturnCamera = useRef<{ center: NaverCoordinate; zoom: number } | null>(null);
  const lastFavoriteKey = useRef<string | null>(null);
  const fittedFavorites = useRef<string | null>(null);
  const movingFavoriteCamera = useRef(false);
  const boardReturnCamera = useRef<{ center: NaverCoordinate; zoom: number; rotation: number } | null>(null);
  const restoringBoardCamera = useRef(false);
  const mapReady = useRef(false);
  const stopMarkers = useRef<ReturnType<typeof mountHomeStops> | null>(null);
  const [instance, setInstance] = useState<MapFlowSurface | null>(null);
  const compassRef = useRef<ReturnType<typeof bindNaverMapCompass> | null>(null);
  const failMapRef = useRef<((message: string) => void) | null>(null);
  const [rotation, setRotation] = useState(0);
  const [fittedRouteOverviewKey, setFittedRouteOverviewKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [favoritePaths, setFavoritePaths] = useState<Record<string, Point[][]>>({});
  const onFavoritePathsChange = useCallback((routeId: string, paths: Point[][] | undefined) => {
    setFavoritePaths((current) => {
      if (current[routeId] === paths) return current;
      const next = { ...current };
      if (paths === undefined) delete next[routeId];
      else next[routeId] = paths;
      return next;
    });
  }, []);
  useLayoutEffect(() => {
    latest.current = props;
  }, [props]);
  useEffect(() => {
    let active = true;
    let failed = false;
    let dispose = () => {};
    function failMap(message: string) {
      if (!active || failed) return;
      failed = true;
      try {
        dispose();
      } catch {
        // SDK가 정리 중에도 실패하면 화면에서 남은 지도 요소를 치운다.
        element.current?.replaceChildren();
      }
      setInstance(null);
      setError(message);
      latest.current.onSurfaceChange({ status: "error", surface: null });
    }
    failMapRef.current = failMap;
    const unsubscribe = subscribeNaverMapAuthFailure(() => failMap("지도 인증을 확인하지 못했어요"));
    void loadNaverMaps(__NAVER_MAP_KEY_ID__)
      .then((maps) => {
        if (!active || failed || !element.current) return;
        const host = element.current;
        const [longitude, latitude] = latest.current.point;
        const map = new maps.Map(host, {
          center: new maps.LatLng(latitude, longitude),
          zoom: 14,
          size: new maps.Size(host.clientWidth, host.clientHeight),
          zoomControl: false,
          scaleControl: false,
          mapDataControl: false,
          logoControlOptions: { position: maps.Position.BOTTOM_LEFT },
          overlayZoomEffect: null,
          gl: true,
          disableRotateTilt: false,
          scrollWheel: true,
          disableDoubleClickZoom: false,
        });
        const reportTiles = () => {
          const { width, height } = map.getSize();
          const projection = map.getProjection();
          if (!projection || map.getZoom() < MIN_STATION_ZOOM || width <= 0 || height <= 0) {
            latest.current.onStationTilesChange("");
            return;
          }
          const corners = [
            new maps.Point(0, 0),
            new maps.Point(width, 0),
            new maps.Point(0, height),
            new maps.Point(width, height),
          ].map((point) => projection.fromOffsetToCoord(point));
          // 지도를 돌리면 경도와 위도의 최솟값·최댓값이 다른 꼭짓점에 놓일 수 있다.
          const longitudes = corners.map((corner) => corner.lng());
          const latitudes = corners.map((corner) => corner.lat());
          latest.current.onStationTilesChange(
            homeStationTileKey(
              [Math.min(...longitudes), Math.min(...latitudes)],
              [Math.max(...longitudes), Math.max(...latitudes)],
            ),
          );
        };
        const listener = maps.Event.addListener(map, "idle", () => {
          movingFavoriteCamera.current = false;
          const center = map.getCenter();
          const point: Point = [center.lng(), center.lat()];
          const target = pendingStopCenter.current;
          pendingStopCenter.current = null;
          latest.current.onCenterChange(
            point,
            restoringBoardCamera.current || (target !== null && distanceMeters(point, target) < 1),
          );
          reportTiles();
        });
        mapReady.current = false;
        const markReady = () => {
          if (mapReady.current) return;
          mapReady.current = true;
          latest.current.onSurfaceChange({ status: "ready", surface: { map, maps, element: host } });
        };
        const initListener = maps.Event.addListener(map, "init", markReady);
        if (!map.getOptions("gl")) markReady();
        const deselect = () => latest.current.onMapBlankClick?.();
        const clickListener = maps.Event.addListener(map, "click", deselect);
        const tapListener = maps.Event.addListener(map, "tap", deselect);
        const resize = new ResizeObserver(() => {
          if (window.naver?.maps !== maps) return;
          map.setSize(new maps.Size(host.clientWidth, host.clientHeight));
          reportTiles();
        });
        resize.observe(host);
        dispose = () => {
          dispose = () => {};
          resize.disconnect();
          mapReady.current = false;
          // 인증에 실패하면 SDK가 지도와 이벤트를 먼저 제거한다.
          if (window.naver?.maps !== maps) return;
          maps.Event.removeListener(initListener);
          maps.Event.removeListener(listener);
          maps.Event.removeListener(clickListener);
          maps.Event.removeListener(tapListener);
          map.destroy();
        };
        setInstance({ map, maps, element: host });
      })
      .catch(() => failMap("지도를 불러오지 못했어요."));
    return () => {
      active = false;
      failMapRef.current = null;
      unsubscribe();
      dispose();
      latest.current.onSurfaceChange({ status: "loading", surface: null });
    };
  }, []);
  useEffect(() => {
    if (instance) instance.map.setCenter(new instance.maps.LatLng(props.point[1], props.point[0]));
  }, [instance, props.point]);
  const { boardOpen, overlayOpen, favoriteRoutes, routeOverviewKey, routePaths, searchBarRef, sheetSurfaceRef } = props;
  useEffect(() => {
    if (instance === null || boardOpen) return;
    const compass = bindNaverMapCompass(instance, setRotation, () => failMapRef.current?.("지도를 불러오지 못했어요."));
    compassRef.current = compass;
    return () => {
      compassRef.current = null;
      compass.destroy();
    };
  }, [instance, boardOpen]);
  const favoriteRouteIds = useMemo(() => [...favoriteRoutes.keys()].sort(), [favoriteRoutes]);
  const favoriteKey = favoriteRouteIds.length === 0 ? null : JSON.stringify([props.favoriteGroupId, favoriteRouteIds]);
  const selectedStopId = props.selectedStop?.id ?? null;
  const favoriteOverviewPaths = useMemo(
    () =>
      favoriteRouteIds.every((id) => favoritePaths[id] !== undefined)
        ? favoriteRouteIds.flatMap((id) => favoritePaths[id] ?? [])
        : [],
    [favoritePaths, favoriteRouteIds],
  );
  const hasUnavailableFavoriteRoutes = favoriteRouteIds.some((id) => favoritePaths[id]?.length === 0);
  const overviewPaths = routeOverviewKey === null ? favoriteOverviewPaths : routePaths;
  useLayoutEffect(
    () => () => {
      if (!movingFavoriteCamera.current) return;
      movingFavoriteCamera.current = false;
      if (instance && window.naver?.maps === instance.maps) instance.map.stop();
    },
    [instance, favoriteKey, overlayOpen, selectedStopId],
  );
  useLayoutEffect(() => {
    if (!instance) return;
    const { map } = instance;
    if (boardOpen) {
      boardReturnCamera.current ??= {
        center: map.getCenter(),
        zoom: map.getZoom(),
        rotation: map.getRotation(),
      };
    } else if (boardReturnCamera.current) {
      const camera = boardReturnCamera.current;
      boardReturnCamera.current = null;
      restoringBoardCamera.current = true;
      try {
        map.setZoom(camera.zoom, false);
        if (map.getRotation() !== camera.rotation) map.setRotation(camera.rotation);
        pendingStopCenter.current = [camera.center.lng(), camera.center.lat()];
        map.setCenter(camera.center);
      } finally {
        restoringBoardCamera.current = false;
      }
    }
  }, [boardOpen, instance]);

  useEffect(() => {
    if (!instance) return;
    if (overlayOpen) {
      if (lastFavoriteKey.current !== null) fittedFavorites.current = lastFavoriteKey.current;
      return;
    }
    lastFavoriteKey.current = favoriteKey;
    const { map, maps } = instance;
    if (favoriteKey === null) fittedFavorites.current = null;
    if (routeOverviewKey === null && favoriteKey === null) {
      const camera = overviewReturnCamera.current;
      if (camera) {
        overviewReturnCamera.current = null;
        map.setZoom(camera.zoom, false);
        map.setCenter(camera.center);
      }
      return;
    }
    if (overviewPaths.length === 0 || (favoriteKey !== null && fittedFavorites.current === favoriteKey)) return;

    let frame = 0;
    const fit = () => {
      frame = 0;
      if (window.naver?.maps !== maps) return;
      if (favoriteKey !== null && fittedFavorites.current === favoriteKey) return;
      const host = element.current;
      const search = searchBarRef.current;
      const sheet = sheetSurfaceRef.current;
      if (!host || !search || !sheet || latest.current.selectedStop) return;
      const sheetStyle = getComputedStyle(sheet);
      const sheetHeight = parseFloat(sheetStyle.getPropertyValue(styles.sheetHeight)) || 0;
      const fractionalZoom = map.getOptions("gl");
      const verticalInset = routeOverviewKey !== null || fractionalZoom ? 12 : 0;
      const bottom = sheetHeight + parseFloat(sheetStyle.paddingBottom) + verticalInset;
      const top = Math.max(0, search.getBoundingClientRect().bottom - host.getBoundingClientRect().top) + verticalInset;
      const { width, height } = map.getSize();
      const visibleHeight = height - top - bottom;
      if (visibleHeight <= 0 || width <= 32) return;
      const projection = map.getProjection();
      const zoom = map.getZoom();
      const verticalOffset = (bottom - top) / 2;
      const bounds = { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity };
      for (const path of overviewPaths) {
        for (const [longitude, latitude] of path) {
          const { x, y } = projection.fromCoordToOffset(new maps.LatLng(latitude, longitude));
          bounds.left = Math.min(bounds.left, x);
          bounds.right = Math.max(bounds.right, x);
          bounds.top = Math.min(bounds.top, y);
          bounds.bottom = Math.max(bounds.bottom, y);
        }
      }
      if (!Number.isFinite(bounds.left)) return;
      // 정수 줌의 경계에서는 투영 좌표의 반올림 오차 1px을 허용한다.
      const scale = Math.min(
        (width - 32) / Math.max(1, bounds.right - bounds.left),
        (visibleHeight + (fractionalZoom ? 0 : 1)) / Math.max(1, bounds.bottom - bounds.top),
      );
      const fitZoom = zoom + Math.log2(scale);
      const targetZoom = Math.min(14, fractionalZoom ? fitZoom : Math.floor(fitZoom));
      // 지도 전체의 중심 대신 검색창과 시트 사이에 노선의 중심을 맞춘다.
      const center = projection.fromOffsetToCoord(
        new maps.Point(
          (bounds.left + bounds.right) / 2,
          (bounds.top + bounds.bottom) / 2 + verticalOffset * 2 ** (zoom - targetZoom),
        ),
      );
      overviewReturnCamera.current ??= { center: map.getCenter(), zoom };
      if (favoriteKey !== null && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        movingFavoriteCamera.current = true;
        map.morph(center, targetZoom, { duration: 460 });
      } else {
        map.setZoom(targetZoom, false);
        map.setCenter(center);
      }
      if (routeOverviewKey !== null) setFittedRouteOverviewKey(routeOverviewKey);
      fittedFavorites.current = favoriteKey;
    };
    const schedule = () => {
      if (frame === 0) frame = requestAnimationFrame(fit);
    };
    const listener = maps.Event.addListener(map, "init", schedule);
    if (mapReady.current) schedule();
    return () => {
      cancelAnimationFrame(frame);
      if (window.naver?.maps === maps) maps.Event.removeListener(listener);
    };
  }, [
    instance,
    overlayOpen,
    routeOverviewKey,
    favoriteKey,
    overviewPaths,
    searchBarRef,
    sheetSurfaceRef,
    selectedStopId,
  ]);
  const stopLongitude = props.selectedStop?.point[0];
  const stopLatitude = props.selectedStop?.point[1];
  useEffect(() => {
    const host = element.current;
    const searchBar = searchBarRef.current;
    if (!instance || !host || !searchBar || stopLongitude === undefined || stopLatitude === undefined) return;
    const { map, maps } = instance;
    const projection = map.getProjection();
    const { height } = map.getSize();
    const hostTop = host.getBoundingClientRect().top;
    const viewport = window.visualViewport;
    const viewportHeight = viewport?.height ?? window.innerHeight;
    const sheetTop = (viewport?.offsetTop ?? 0) + viewportHeight * (1 - styles.stopSheetViewportRatio);
    const visibleBottom = Math.max(0, Math.min(height, sheetTop - hostTop));
    const visibleTop = Math.min(visibleBottom, searchBar.getBoundingClientRect().bottom - hostTop);
    const pinY = (visibleTop + visibleBottom) / 2;
    const stop = projection.fromCoordToOffset(new maps.LatLng(stopLatitude, stopLongitude));
    const center = projection.fromOffsetToCoord(new maps.Point(stop.x, stop.y + height / 2 - pinY));
    pendingStopCenter.current = [center.lng(), center.lat()];
    map.setCenter(center);
    return () => {
      pendingStopCenter.current = null;
    };
  }, [instance, searchBarRef, selectedStopId, stopLatitude, stopLongitude]);
  useLayoutEffect(() => {
    if (!instance || props.stops.length === 0) return;
    const layer = mountHomeStops(instance.map, instance.maps, props.stops, (id) => latest.current.onSelectStop(id));
    layer.select(latest.current.selectedStop?.id ?? null);
    stopMarkers.current = layer;
    return () => {
      stopMarkers.current = null;
      layer.dispose();
    };
  }, [instance, props.stops]);
  useEffect(() => {
    stopMarkers.current?.select(selectedStopId);
  }, [selectedStopId]);
  useEffect(() => {
    if (!instance || !props.location) return;
    const { maps, map } = instance;
    const circle = new maps.Circle({
      map,
      center: new maps.LatLng(props.location[1], props.location[0]),
      radius: 35,
      fillColor: "#3486F5",
      fillOpacity: 0.35,
      strokeColor: "#FFFFFF",
      strokeOpacity: 1,
      strokeWeight: 2,
      zIndex: 4,
    });
    return () => {
      if (window.naver?.maps === maps) circle.setMap(null);
    };
  }, [instance, props.location]);
  return (
    <section {...maskedPropsFor(styles.map)} aria-label="지도">
      <div ref={element} className={styles.mapCanvas} />
      {instance && routeOverviewKey === fittedRouteOverviewKey && routePaths.length > 0 && (
        <HomeRouteNetwork {...instance} paths={routePaths} />
      )}
      {instance &&
        [...favoriteRoutes].map(([routeId, direction]) => (
          <FavoriteMapRoute
            key={routeId}
            {...instance}
            routeId={routeId}
            direction={direction}
            onPathsChange={onFavoritePathsChange}
          />
        ))}
      {instance && !error && !overlayOpen && hasUnavailableFavoriteRoutes && (
        <p className={styles.favoriteMapNotice} role="status">
          지도에 표시할 수 없는 즐겨찾기 노선이 있어요.
        </p>
      )}
      {(!instance || error) && (
        <p className={styles.mapNotice} aria-live="polite">
          {error ?? "주변 지도를 불러오고 있어요…"}
        </p>
      )}
      {!boardOpen && (
        <div className={styles.mapControlPosition} role="group" aria-label="지도 도구" tabIndex={-1}>
          {instance !== null && !error && isMapRotated(rotation) && (
            <MapCompassButton rotation={rotation} onReset={() => compassRef.current?.resetNorth()} />
          )}
          <MapLocationButton
            onClick={props.onLocate}
            disabled={props.locating}
            aria-label={props.locating ? "현재 위치 확인 중" : "현재 위치로 이동"}
          />
        </div>
      )}
    </section>
  );
}
