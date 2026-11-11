import { useCallback, useMemo, useReducer, useRef, useState, type MouseEvent } from "react";
import { matchPath, useLocation, useOutlet } from "react-router";
import { Brand } from "@/shared/components/Brand";
import { BottomNavigation } from "@/shared/components/BottomNavigation";
import type { Direction } from "@/shared/api/routeForecast.types";
import type { FavoriteGroup, HomeFavorites } from "@/shared/api/favorites.types";
import { latestSuccessOf, type LatestSuccess } from "@/shared/api/polledResource";
import type { PolledRequest } from "@/shared/api/usePolledRequest";
import { useFavorites, useHomeFavorites } from "@/shared/hooks/useFavorites";
import { MapFlowSurfaceContext, type MapFlowSurfaceState } from "@/shared/map/mapFlowSurface";
import { Notice } from "@/shared/components/Notice";
import { RetryButton } from "@/shared/components/RetryButton";
import { paths } from "@/shared/routing/paths";
import { useNavigateKeepingFocus } from "@/shared/routing/focusKeepingNavigation";
import { routeSearchStateFor } from "@/shared/routing/routeSearchEntry";
import { SEARCH_ENTRY_LABEL, SEARCH_ENTRY_PROMPT } from "../routeSelectCopy";
import { RouteNetworkTitle } from "../RouteNetworkPage";
import { WhatsNewOnEntry } from "./WhatsNewOnEntry";
import { HomeBottomSheet, type HomeSheetControls, type HomeSheetSnap } from "../home/HomeBottomSheet";
import { HomeMap } from "../home/HomeMap";
import { HomeStopRoutes } from "../home/HomeStopRoutes";
import { HomeOverview } from "../home/HomeOverview";
import { DEFAULT_LOCATION, distanceMeters, type HomeStop, type Point } from "../home/homeMapData";
import { useHomeMapData } from "../home/useHomeMapData";
import { useHomeStations } from "../home/useHomeStations";
import * as homeStyles from "../home/HomeScreen.css";
import * as styles from "./RouteSelectScreen.css";

export interface FavoriteGroupContext {
  favoriteGroup: FavoriteGroup | undefined;
  selectFavoriteGroup: (groupId: string) => void;
  summary: PolledRequest<HomeFavorites>;
  retrySummary: () => void;
  displayedGroup: FavoriteGroup | undefined;
  displayedSummary: LatestSuccess<HomeFavorites> | null;
  switchingGroup: boolean;
}

export interface FavoriteGroupSelection {
  memberId: string;
  groupId: string;
}

export function RouteSelectScreen({
  favoriteSelection,
  onFavoriteSelection,
}: {
  favoriteSelection: FavoriteGroupSelection | null;
  onFavoriteSelection: (selection: FavoriteGroupSelection) => void;
}) {
  const location = useLocation();
  const isHome = location.pathname === paths.routeSelect;
  const boardOpen = matchPath(paths.board, location.pathname) !== null;
  const searchOpen = location.pathname === paths.routeSearch;
  const overlayOpen = boardOpen || searchOpen;
  const BrandHeading = isHome ? "h1" : "div";
  const isRouteMap = location.pathname === paths.routeMap;
  const isFavorites = location.pathname === paths.favorites;
  const showStops = isHome || isFavorites;
  const { groups, memberId } = useFavorites();
  const selectedGroupId = favoriteSelection?.memberId === memberId ? favoriteSelection.groupId : null;
  const favoriteGroup =
    groups.find((group) => group.id === selectedGroupId) ??
    groups.find((group) => group.favorites.length > 0) ??
    groups[0];
  const mainRef = useRef<HTMLElement>(null);
  const searchBarRef = useRef<HTMLElement>(null);
  const sheetSurfaceRef = useRef<HTMLDivElement>(null);
  const sheetControlsRef = useRef<HomeSheetControls>(null);
  const navigationRef = useRef<HTMLElement>(null);
  const [sheet, setSheet] = useState<{
    key: string;
    tabSnap: HomeSheetSnap;
    station: { stop: HomeStop; snap: HomeSheetSnap } | null;
  } | null>(null);
  const currentSheet = sheet?.key === location.key ? sheet : null;
  const selectedStop = currentSheet?.station?.stop ?? null;
  const [summaryRevision, retrySummary] = useReducer((value: number) => value + 1, 0);
  const summary = useHomeFavorites(
    isFavorites && favoriteGroup && selectedStop === null ? { kind: "group", groupId: favoriteGroup.id } : null,
    summaryRevision,
  );
  const latestSummary = latestSuccessOf(summary.resource);
  const [previousSummary, setPreviousSummary] = useState<{
    locationKey: string;
    memberId: string;
    groupId: string;
    data: LatestSuccess<HomeFavorites>;
  } | null>(null);
  const previousGroup =
    previousSummary?.memberId === memberId && previousSummary.locationKey === location.key
      ? groups.find((group) => group.id === previousSummary.groupId)
      : undefined;
  const switchingGroup = summary.resource.status === "pending" && previousGroup !== undefined;
  const displayedGroup = switchingGroup ? previousGroup : favoriteGroup;
  const displayedSummary = switchingGroup ? (previousSummary?.data ?? null) : latestSummary;
  const outlet = useOutlet({
    favoriteGroup,
    selectFavoriteGroup: (groupId) => {
      if (memberId === null) return;
      if (favoriteGroup && favoriteGroup.id !== groupId && latestSummary) {
        setPreviousSummary({ locationKey: location.key, memberId, groupId: favoriteGroup.id, data: latestSummary });
      }
      onFavoriteSelection({ memberId, groupId });
    },
    summary,
    retrySummary,
    displayedGroup,
    displayedSummary,
    switchingGroup,
  } satisfies FavoriteGroupContext);
  const favoriteRoutes = useMemo(() => {
    const directions = new Map<string, Direction>();
    if (isFavorites && memberId && displayedGroup) {
      for (const { routeId, direction } of displayedGroup.favorites) {
        if (!directions.has(routeId)) directions.set(routeId, direction);
      }
    }
    return directions;
  }, [displayedGroup, isFavorites, memberId]);
  const tabSnap = currentSheet?.tabSnap ?? "summary";
  const snap = currentSheet?.station?.snap ?? tabSnap;
  const [cameraTarget, setCameraTarget] = useState<Point>(DEFAULT_LOCATION);
  const [mapKey, setMapKey] = useState(0);
  const [mapSurface, setMapSurface] = useState<MapFlowSurfaceState>({ status: "loading", surface: null });
  const mapCenter = useRef<Point>(DEFAULT_LOCATION);
  const [weatherPoint, setWeatherPoint] = useState<Point>(DEFAULT_LOCATION);
  const [currentLocation, setCurrentLocation] = useState<Point | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationMessage, setLocationMessage] = useState<string | null>(null);
  const [stationTileKey, setStationTileKey] = useState("");
  const { stops: fallbackStops, routePaths } = useHomeMapData();
  const {
    stops,
    hasError: stationTilesFailed,
    retry: retryStationTiles,
  } = useHomeStations(showStops ? stationTileKey : "", fallbackStops);
  const sheetLabel = selectedStop
    ? "정류장 노선 안내"
    : isRouteMap
      ? "서비스 중인 노선"
      : location.pathname === paths.mypage
        ? "마이페이지"
        : isHome
          ? "홈 정보"
          : "즐겨찾기 메뉴";

  const setSheetHeight = useCallback((height: number) => {
    mainRef.current?.style.setProperty(homeStyles.sheetHeight, `${height}px`);
  }, []);

  function closeStop() {
    if (selectedStop) setSheet({ key: location.key, tabSnap, station: null });
  }

  function updateMapCenter(point: Point, stopFocus: boolean) {
    if (distanceMeters(point, mapCenter.current) < 1) return;
    mapCenter.current = point;
    if (!stopFocus) closeStop();
  }

  function resetHome(event: MouseEvent<HTMLAnchorElement>) {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    setSheet(null);
    mapCenter.current = DEFAULT_LOCATION;
    setCameraTarget(DEFAULT_LOCATION);
    setMapKey((key) => key + 1);
  }

  function locate() {
    if (!navigator.geolocation) {
      setLocationMessage("현재 위치를 지원하지 않는 환경이에요. 지도를 움직여 지역을 선택해 주세요.");
      return;
    }
    setLocating(true);
    setLocationMessage(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const next: Point = [position.coords.longitude, position.coords.latitude];
        setCurrentLocation(next);
        setCameraTarget(next);
        mapCenter.current = next;
        closeStop();
        setLocating(false);
      },
      (failure) => {
        setLocating(false);
        setLocationMessage(
          failure.code === 1
            ? "위치 권한이 꺼져 있어요. 지도를 움직여 지역을 선택할 수 있어요."
            : "현재 위치를 확인하지 못했어요. 다시 눌러주세요.",
        );
      },
      { timeout: 10_000, maximumAge: 60_000, enableHighAccuracy: false },
    );
  }

  const notice: unknown = location.state?.notice;
  const navigateKeepingFocus = useNavigateKeepingFocus();

  // iOS에서 숫자 키패드를 열 수 있도록 입력창을 누른 뒤 검색 화면으로 이동한다.
  const openRouteSearch = () => navigateKeepingFocus(paths.routeSearch, { mapFlow: true });
  const openRouteSearchWith = (input: string) =>
    navigateKeepingFocus(paths.routeSearch, { ...routeSearchStateFor(input), mapFlow: true });

  return (
    <div className={homeStyles.page}>
      <main
        ref={mainRef}
        className={homeStyles.main}
        data-board-open={boardOpen}
        data-station-open={selectedStop !== null || boardOpen}
        role={boardOpen ? "presentation" : undefined}
        inert={searchOpen}
      >
        <div className={homeStyles.content} inert={!overlayOpen && snap === "full"}>
          <header ref={searchBarRef} className={homeStyles.floatingSearch} hidden={overlayOpen}>
            <BrandHeading className={homeStyles.brandHeading}>
              <Brand glass onClick={resetHome} />
            </BrandHeading>
            <span className={homeStyles.searchDivider} aria-hidden="true" />
            <label className={styles.searchEntry}>
              <input
                className={styles.searchEntryText}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                placeholder={SEARCH_ENTRY_PROMPT}
                aria-label={SEARCH_ENTRY_LABEL}
                onClick={openRouteSearch}
                onChange={(event) => openRouteSearchWith(event.currentTarget.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") openRouteSearch();
                }}
              />
              <span className={styles.searchEntryPrompt} aria-hidden="true">
                {SEARCH_ENTRY_PROMPT}
              </span>
              <svg className={styles.searchIcon} viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.35-4.35" />
              </svg>
            </label>
          </header>
          <HomeMap
            key={mapKey}
            point={cameraTarget}
            location={currentLocation}
            stops={showStops ? stops : []}
            routePaths={isRouteMap ? routePaths : []}
            routeOverviewKey={isRouteMap ? location.key : null}
            favoriteGroupId={isFavorites ? (displayedGroup?.id ?? null) : null}
            favoriteRoutes={favoriteRoutes}
            overlayOpen={overlayOpen}
            boardOpen={boardOpen}
            onSurfaceChange={setMapSurface}
            sheetSurfaceRef={sheetSurfaceRef}
            selectedStop={selectedStop ?? undefined}
            searchBarRef={searchBarRef}
            onCenterChange={updateMapCenter}
            onStationTilesChange={setStationTileKey}
            onSelectStop={(id) => {
              if (selectedStop?.id === id) {
                closeStop();
                return;
              }
              const stop = stops.find((item) => item.id === id);
              if (stop) setSheet({ key: location.key, tabSnap, station: { stop, snap: "detail" } });
            }}
            onMapBlankClick={selectedStop ? closeStop : null}
            onLocate={locate}
            locating={locating}
          />
          {!overlayOpen && mapSurface.status === "ready" && stationTilesFailed && (
            <div className={homeStyles.stationMapError}>
              <span role="status">주변 정류장을 모두 불러오지 못했어요.</span>
              <RetryButton onRetry={retryStationTiles}>정류장 다시 불러오기</RetryButton>
            </div>
          )}
          {!overlayOpen && locationMessage && (
            <p className={homeStyles.locationMessage} role="status">
              {locationMessage}
            </p>
          )}
        </div>
        {!overlayOpen && (
          <HomeBottomSheet
            controlsRef={sheetControlsRef}
            surfaceRef={sheetSurfaceRef}
            snap={snap}
            onSnapChange={(next) => {
              if (selectedStop) {
                if (next === "collapsed") closeStop();
                else setSheet({ key: location.key, tabSnap, station: { stop: selectedStop, snap: next } });
                return;
              }
              if (next === "collapsed") {
                if (sheetSurfaceRef.current?.contains(document.activeElement)) {
                  navigationRef.current
                    ?.querySelector<HTMLAnchorElement>('[aria-current="page"]')
                    ?.focus({ preventScroll: true });
                }
              }
              setSheet({ key: location.key, tabSnap: next, station: null });
            }}
            onHeightChange={setSheetHeight}
            label={sheetLabel}
            contentKey={selectedStop?.id ?? location.pathname}
            locationKey={location.key}
            station={selectedStop}
            home={isHome}
            showTabClose={isRouteMap || location.pathname === paths.favoriteGroups}
            actionTitle={isRouteMap && selectedStop === null && <RouteNetworkTitle />}
          >
            {selectedStop ? (
              <HomeStopRoutes stop={selectedStop} sheetSnap={snap === "collapsed" ? "detail" : snap} />
            ) : isHome ? (
              snap !== "collapsed" && (
                <HomeOverview point={weatherPoint} stops={stops} onRefresh={() => setWeatherPoint(mapCenter.current)} />
              )
            ) : (
              outlet
            )}
          </HomeBottomSheet>
        )}
      </main>
      {searchOpen && <div className={homeStyles.searchOverlay}>{outlet}</div>}
      {boardOpen && <MapFlowSurfaceContext value={mapSurface}>{outlet}</MapFlowSurfaceContext>}
      {!overlayOpen && !selectedStop && (
        <BottomNavigation ref={navigationRef} onActiveTabPress={() => sheetControlsRef.current?.advance()} />
      )}
      {typeof notice === "string" && <Notice key={location.key} message={notice} />}
      {isHome && <WhatsNewOnEntry />}
    </div>
  );
}
