import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import type { Direction } from "@/shared/api/routeForecast.types";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { MapLocationButton } from "@/shared/components/MapLocationButton";
import { recordInputMethod } from "@/shared/input-method/inputMethod";
import { createNativeMapChannel, type NativeMapChannel } from "@/shared/native-map/bridge";
import { rectFor, startOverlayLayoutBridge } from "@/shared/native-map/overlayLayoutBridge";
import type { NativeCoordinate, NativeHomeMapContent, NativeMapCamera } from "@/shared/native-map/protocol";
import { useFavoriteMapGeometry } from "./FavoriteMapRoute";
import type { HomeMap } from "./HomeMap";
import type { Point } from "./homeMapData";
import { homeRouteRuns } from "./homeRouteRuns";
import { homeStationTileKey, MIN_STATION_ZOOM } from "./homeStationTiles";
import * as styles from "./HomeScreen.css";

type HomePaths = NativeHomeMapContent["paths"];
interface NativeHomeMapProps extends ComponentProps<typeof HomeMap> {
  paused: boolean;
  onLocation: (point: Point) => void;
}

function coordinateFrom([longitude, latitude]: Point): NativeCoordinate {
  return { latitude, longitude };
}

function boundsFor(paths: HomePaths) {
  let west = Infinity;
  let south = Infinity;
  let east = -Infinity;
  let north = -Infinity;
  for (const path of paths) {
    for (const { latitude, longitude } of path.coordinates) {
      west = Math.min(west, longitude);
      south = Math.min(south, latitude);
      east = Math.max(east, longitude);
      north = Math.max(north, latitude);
    }
  }
  return Number.isFinite(west)
    ? { southwest: { latitude: south, longitude: west }, northeast: { latitude: north, longitude: east } }
    : null;
}

function visibleAreaFor(region: HTMLElement, props: NativeHomeMapProps, stopFocus: boolean) {
  const bounds = region.getBoundingClientRect();
  const search = props.searchBarRef.current?.getBoundingClientRect();
  const viewport = window.visualViewport;
  const top = Math.max(bounds.top, search?.bottom ?? bounds.top);
  const sheetTop = stopFocus
    ? (viewport?.offsetTop ?? 0) + (viewport?.height ?? window.innerHeight) * (1 - styles.stopSheetViewportRatio)
    : props.sheetSurfaceRef.current?.querySelector("#home-bottom-sheet")?.getBoundingClientRect().top;
  const bottom = Math.min(bounds.bottom, sheetTop ?? bounds.bottom);
  if (bottom <= top) return undefined;
  return rectFor({ left: bounds.left, top, width: bounds.width, height: bottom - top });
}

function NativeFavoritePaths({
  routeId,
  direction,
  onPathsChange,
}: {
  routeId: string;
  direction: Direction;
  onPathsChange: (routeId: string, paths: HomePaths | undefined) => void;
}) {
  const { data, fullPath, settled } = useFavoriteMapGeometry(routeId);
  const paths = useMemo<HomePaths>(() => {
    if (data === null) return [];
    const paths = data.paths.map((path) => ({
      coordinates: path.coordinates,
      active: path.direction === direction,
      arrows: path.direction === direction,
    }));
    if (fullPath) paths.unshift({ coordinates: fullPath, active: false, arrows: false });
    return paths.filter((path) => path.coordinates.length > 1);
  }, [data, direction, fullPath]);
  useEffect(() => {
    if (settled) onPathsChange(routeId, paths);
  }, [settled, onPathsChange, routeId, paths]);
  useEffect(() => () => onPathsChange(routeId, undefined), [onPathsChange, routeId]);
  return null;
}

export function NativeHomeMap(props: NativeHomeMapProps) {
  const region = useRef<HTMLElement>(null);
  const channel = useRef<NativeMapChannel | null>(null);
  const camera = useRef<NativeMapCamera>({ center: coordinateFrom(props.point), zoom: 14, heading: 0 });
  const requestedPoint = useRef(props.point);
  const overviewReturn = useRef<NativeMapCamera | null>(null);
  const fittedOverview = useRef<string | null>(null);
  const locationRequest = useRef(0);
  const pendingLocation = useRef<number | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState("");
  const [favoritePaths, setFavoritePaths] = useState<Record<string, HomePaths>>({});
  const onFavoritePathsChange = useCallback((routeId: string, paths: HomePaths | undefined) => {
    setFavoritePaths((current) => {
      if (current[routeId] === paths) return current;
      const next = { ...current };
      if (paths === undefined) delete next[routeId];
      else next[routeId] = paths;
      return next;
    });
  }, []);

  const favoriteIds = useMemo(() => [...props.favoriteRoutes.keys()].sort(), [props.favoriteRoutes]);
  const favoriteKey = favoriteIds.length === 0 ? null : JSON.stringify([props.favoriteGroupId, favoriteIds]);
  const overviewKey = props.routeOverviewKey ?? favoriteKey;
  const networkPaths = useMemo<HomePaths>(
    () =>
      homeRouteRuns(props.routePaths).map((path) => ({
        coordinates: path.map(coordinateFrom),
        active: true,
        arrows: false,
      })),
    [props.routePaths],
  );
  const favoritesSettled = favoriteIds.every((id) => favoritePaths[id] !== undefined);
  const nativePaths = useMemo<HomePaths>(
    () => (props.routeOverviewKey !== null ? networkPaths : favoriteIds.flatMap((id) => favoritePaths[id] ?? [])),
    [props.routeOverviewKey, networkPaths, favoriteIds, favoritePaths],
  );
  const selectedStopId = props.selectedStop?.id ?? null;
  const stations = useMemo<NativeHomeMapContent["stations"]>(
    () => ({
      stops: props.stops.map((stop) => ({ id: stop.id, name: stop.name, coordinate: coordinateFrom(stop.point) })),
      selectedStopId,
    }),
    [props.stops, selectedStopId],
  );
  const location = useMemo(
    () => ({ coordinate: props.location ? coordinateFrom(props.location) : null }),
    [props.location],
  );
  const content = useMemo<NativeHomeMapContent>(
    () => ({ stations, paths: nativePaths, location }),
    [stations, nativePaths, location],
  );
  const latest = useRef({ props, content });
  const sentContent = useRef<NativeHomeMapContent | null>(null);
  useLayoutEffect(() => {
    latest.current = { props, content };
  }, [props, content]);

  const { paused, overlayOpen } = props;
  useEffect(() => {
    const element = region.current;
    if (paused || element === null) return;
    setStatus("loading");
    setLocating(false);
    const map = createNativeMapChannel();
    channel.current = map;
    let disposed = false;
    let stopLayout = () => {};
    const root = document.documentElement;
    const timer = window.setTimeout(() => fail("주변 지도를 불러오지 못했어요"), 15_000);
    const unsubscribe = map.subscribe((event) => {
      const { props } = latest.current;
      switch (event.type) {
        case "ready":
          window.clearTimeout(timer);
          root.dataset.nativeHomeMap = "true";
          setStatus("ready");
          props.onSurfaceChange({ status: "ready", surface: null, native: true });
          break;
        case "homeViewport": {
          const { camera: next, corners, gesture } = event.payload;
          camera.current = next;
          props.onCenterChange([next.center.longitude, next.center.latitude], !gesture);
          if (next.zoom < MIN_STATION_ZOOM) {
            props.onStationTilesChange("");
            break;
          }
          const longitudes = corners.map(({ longitude }) => longitude);
          const latitudes = corners.map(({ latitude }) => latitude);
          props.onStationTilesChange(
            homeStationTileKey(
              [Math.min(...longitudes), Math.min(...latitudes)],
              [Math.max(...longitudes), Math.max(...latitudes)],
            ),
          );
          break;
        }
        case "homeStopPress":
          recordInputMethod("pointer");
          props.onSelectStop(event.payload.id);
          break;
        case "mapPress":
          recordInputMethod("pointer");
          props.onMapBlankClick?.();
          break;
        case "locationResult": {
          const { requestId, coordinate, error } = event.payload;
          if (pendingLocation.current !== requestId) break;
          pendingLocation.current = null;
          setLocating(false);
          if (coordinate) props.onLocation([coordinate.longitude, coordinate.latitude]);
          else
            setLocationError(
              error === "denied"
                ? "위치 권한이 꺼져 있어요. 지도를 움직여 지역을 선택할 수 있어요."
                : "현재 위치를 확인하지 못했어요. 다시 눌러주세요.",
            );
          break;
        }
        case "error":
          fail(event.payload.message);
          break;
      }
    });
    function dispose() {
      if (disposed) return;
      disposed = true;
      window.clearTimeout(timer);
      stopLayout();
      unsubscribe();
      pendingLocation.current = null;
      channel.current = null;
      map.send("unmount", {});
      map.destroy();
      delete root.dataset.nativeHomeMap;
    }
    function fail(message: string) {
      dispose();
      setStatus("error");
      setError(message);
      latest.current.props.onSurfaceChange({ status: "error", surface: null });
    }
    sentContent.current = latest.current.content;
    map.send("mountHome", { camera: camera.current, content: latest.current.content });
    stopLayout = startOverlayLayoutBridge(map, element, (bounds) => {
      const sheet = latest.current.props.sheetSurfaceRef.current?.querySelector("#home-bottom-sheet");
      const navigation = document.querySelector('nav[aria-label="주요 메뉴"]');
      let bottom = bounds.bottom;
      for (const overlay of [sheet, navigation]) {
        const rect = overlay?.getBoundingClientRect();
        if (rect && rect.height > 0) bottom = Math.min(bottom, rect.top);
      }
      return Math.max(0, bounds.bottom - bottom);
    });
    return dispose;
  }, [paused]);

  useEffect(() => {
    const map = channel.current;
    if (!map || sentContent.current === content) return;
    const previous = sentContent.current;
    map.send("updateHome", {
      ...(previous?.stations !== content.stations && { stations: content.stations }),
      ...(previous?.paths !== content.paths && { paths: content.paths }),
      ...(previous?.location !== content.location && { location: content.location }),
    });
    sentContent.current = content;
  }, [content, paused]);

  useEffect(() => {
    if (requestedPoint.current === props.point) return;
    requestedPoint.current = props.point;
    const coordinate = coordinateFrom(props.point);
    camera.current = { ...camera.current, center: coordinate };
    channel.current?.send("homeCamera", { command: "center", coordinate });
  }, [props.point]);

  useEffect(() => {
    const map = channel.current;
    const element = region.current;
    if (paused || status !== "ready" || !map || !element || overlayOpen || selectedStopId !== null) return;
    if (overviewKey === null) {
      const previous = overviewReturn.current;
      overviewReturn.current = null;
      fittedOverview.current = null;
      if (previous) map.send("homeCamera", { command: "position", camera: previous });
      return;
    }
    if (fittedOverview.current === overviewKey || (favoriteKey !== null && !favoritesSettled)) return;
    const bounds = boundsFor(nativePaths);
    const visible = visibleAreaFor(element, latest.current.props, false);
    if (bounds === null || visible === undefined) return;
    overviewReturn.current ??= camera.current;
    fittedOverview.current = overviewKey;
    map.send("homeCamera", { command: "fit", ...bounds, visible, animated: favoriteKey !== null });
  }, [status, paused, overlayOpen, overviewKey, selectedStopId, favoriteKey, favoritesSettled, nativePaths]);

  const selectedStop = props.selectedStop;
  useEffect(() => {
    const element = region.current;
    if (!element || paused || !selectedStop || overlayOpen || status !== "ready") return;
    const visible = visibleAreaFor(element, latest.current.props, true);
    channel.current?.send("homeCamera", {
      command: "center",
      coordinate: coordinateFrom(selectedStop.point),
      ...(visible && { visible }),
    });
  }, [status, paused, selectedStop, overlayOpen]);

  function locate() {
    const map = channel.current;
    if (!map || pendingLocation.current !== null) return;
    const requestId = ++locationRequest.current;
    pendingLocation.current = requestId;
    setLocationError("");
    setLocating(true);
    map.send("location", { requestId });
  }

  return (
    <section
      ref={region}
      {...maskedPropsFor(styles.nativeMap)}
      aria-label="지도"
      aria-hidden={paused}
      aria-busy={status === "loading"}
    >
      {[...props.favoriteRoutes].map(([routeId, direction]) => (
        <NativeFavoritePaths
          key={routeId}
          routeId={routeId}
          direction={direction}
          onPathsChange={onFavoritePathsChange}
        />
      ))}
      {!paused && status !== "ready" && (
        <p className={styles.mapNotice} aria-live="polite" data-native-map-hit>
          {status === "error" ? error : "주변 지도를 불러오고 있어요…"}
        </p>
      )}
      {!overlayOpen && status === "ready" && favoriteIds.some((id) => favoritePaths[id]?.length === 0) && (
        <p className={styles.favoriteMapNotice} role="status" data-native-map-hit>
          지도에 표시할 수 없는 즐겨찾기 노선이 있어요.
        </p>
      )}
      {!overlayOpen && locationError && (
        <p className={styles.locationMessage} role="status" data-native-map-hit>
          {locationError}
        </p>
      )}
      {!overlayOpen && status === "ready" && (
        <MapLocationButton
          className={styles.locate}
          onClick={locate}
          disabled={locating}
          aria-label={locating ? "현재 위치 확인 중" : "현재 위치로 이동"}
          data-native-map-hit
        />
      )}
    </section>
  );
}
