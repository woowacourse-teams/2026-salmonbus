import { useContext, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { MapFlowSurfaceContext } from "@/shared/map/mapFlowSurface";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import type { EventMap } from "@/shared/analytics/events";
import { track } from "@/shared/analytics/track";
import salmongCrying from "@/shared/assets/images/salmong-logo/salmong-crying.png";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import type { ApiFailure } from "@/shared/api/client";
import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import { fetchRouteWatch } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo, RouteWatch, Vehicle } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { playHaptic } from "@/shared/haptic/haptic";
import {
  boardEntryFrom,
  quietMapLoadingFrom,
  stationSheetFrom,
  type StationSheetEntry,
} from "@/shared/routing/boardEntry";
import { paths } from "@/shared/routing/paths";
import {
  boardFreshnessOf,
  boardNoticeFor,
  directionInfoFor,
  directionViewsFor,
  displayedStopViewsFor,
  serviceStateFor,
  stopViewsFor,
  type BoardFreshness,
  type BoardNotice,
  type BoardScreen,
} from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { liveMotionDurationOf, useFreshLiveVehicles } from "../hooks/useFreshLiveVehicles";
import { createLiveVehicleMotionStore, useLiveVehicleMotion } from "../hooks/useLiveVehicleMotion";
import { useStationArrivals } from "@/shared/hooks/useStationArrivals";
import { expandableRowOf, stopSelectionFrom, type StopSelection } from "./stopSelection";
import type { BoardSheetSnap } from "@/shared/bottom-sheet/boardSheetPolicy";
import { BoardBottomSheet } from "./BoardBottomSheet";
import { BoardRouteSummary } from "./BoardRouteSummary";
import { DirectionTabs } from "./DirectionTabs";
import { StopBoard } from "./StopBoard";
import type { StopExpandInput } from "../analytics/boardVisitPolicy";
import { useBoardVisitEvents } from "../analytics/useBoardVisitEvents";
import { RouteMap } from "../map/RouteMap";
import { useRouteMapData } from "../map/useRouteMapData";
import { useBoardViewport } from "@/shared/bottom-sheet/useBoardViewport";
import { boardNoticeLabel, OUT_OF_SERVICE_NOTICE } from "./verdictCopy";
import * as styles from "./VerdictBoardScreen.css";

type BoardState =
  | { status: "loading" }
  | { status: "error"; failure: ApiFailure }
  | { status: "ready"; watch: RouteWatch; direction: DirectionInfo };

type DirectionSwitchSource = EventMap["direction_switched"]["source"];

const ignoreMapSelection = () => undefined;

interface OperatingBoardProps {
  stationEntry: StationSheetEntry | null;
  board: Board;
  direction: DirectionInfo;
  liveVehicles: readonly Vehicle[];
  liveMotionDurationMs: number;
  switched: boolean;
  freshness: BoardFreshness;
  notice: BoardNotice | null;
  onDirectionChange: (direction: Direction, source: DirectionSwitchSource) => void;
  onStopExpand: (expansion: StopExpandInput) => void;
  onGeometryConflict: () => void;
}

export function VerdictBoardScreen() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  return <RouteBoard key={routeId} routeId={routeId} />;
}

function RouteBoard({ routeId }: { routeId: Board["route"]["id"] }) {
  const shared = useContext(MapFlowSurfaceContext) !== null;
  const [search, setSearch] = useSearchParams();
  const location = useLocation();
  const stationEntry = shared ? stationSheetFrom(location.state) : null;
  const [entry] = useState(() => boardEntryFrom(location.state));
  const [watchRevision, setWatchRevision] = useState(0);
  const revalidateWatch = useRef(false);
  const { resource: watchResource, requestState } = usePolledRequest<RouteWatch>(
    (id, signal) => {
      const refresh = revalidateWatch.current;
      revalidateWatch.current = false;
      return fetchRouteWatch(id, { signal, ...(refresh && { cache: "no-cache" }) });
    },
    routeId,
    watchRevision,
  );
  const latestWatch = latestSuccessOf(watchResource);
  const retrying = requestState === "loading";
  const { vehicles: freshLiveVehicles, expired } = useFreshLiveVehicles(latestWatch);
  const freshness = boardFreshnessOf(watchResource.status, expired);
  const motionDurationMs = liveMotionDurationOf(watchResource);
  const [preferredDirection, setPreferredDirection] = useState<Direction | null>(() =>
    search.get("direction") === "UP" ? "UP" : search.get("direction") === "DOWN" ? "DOWN" : null,
  );
  const [switched, setSwitched] = useState(false);
  const state = boardStateOf(watchResource, preferredDirection);
  const screen = boardScreenOf(state);
  const { trackStopExpanded } = useBoardVisitEvents({ routeId, screen, latestSuccess: latestWatch, entry });

  function selectDirection(next: Direction, source: DirectionSwitchSource) {
    setPreferredDirection(next);
    if (state.status === "ready" && next !== state.direction.id) {
      setSwitched(true);
      track("direction_switched", { route_id: routeId, to_direction: next, source });
      playHaptic("selection");
    }
    setSearch(
      (previous) => {
        if (previous.get("direction") !== next) previous.delete("stopId");
        previous.set("direction", next);
        return previous;
      },
      { replace: true, state: location.state },
    );
  }

  function refreshWatch() {
    revalidateWatch.current = true;
    setWatchRevision((revision) => revision + 1);
  }

  if (state.status === "ready" && screen.kind === "forecast") {
    const serviceState = serviceStateFor(state.watch, state.direction, state.watch.vehicles);
    return (
      <OperatingBoard
        key={state.watch.route.referenceVersionId}
        stationEntry={stationEntry}
        board={state.watch}
        direction={state.direction}
        switched={switched}
        liveVehicles={freshLiveVehicles}
        liveMotionDurationMs={motionDurationMs}
        freshness={freshness}
        notice={boardNoticeFor(serviceState, freshness, state.watch.observedAt)}
        onDirectionChange={selectDirection}
        onStopExpand={trackStopExpanded}
        onGeometryConflict={refreshWatch}
      />
    );
  }

  if (state.status === "ready" && screen.kind === "outOfService") {
    const alternateDirection = state.watch.route.directions.find(
      (candidate) => serviceStateFor(state.watch, candidate, state.watch.vehicles) !== "outOfService",
    );
    return (
      <OutOfServiceSheet
        board={state.watch}
        direction={state.direction.id}
        shared={shared}
        stationEntry={stationEntry}
        onGeometryConflict={refreshWatch}
      >
        <p role="status">{OUT_OF_SERVICE_NOTICE}</p>
        {alternateDirection !== undefined && (
          <button
            type="button"
            className={styles.alternateDirection}
            onClick={() => selectDirection(alternateDirection.id, "outOfService")}
          >
            {alternateDirection.terminalStopName} 방면 보기
          </button>
        )}
      </OutOfServiceSheet>
    );
  }

  const showObservationSheet =
    shared &&
    screen.kind === "error" &&
    screen.failure.kind === "contract" &&
    screen.failure.status === 503 &&
    screen.failure.error.code === "NO_RECENT_OBSERVATION";
  const notice =
    screen.kind === "error" ? (
      <StopBoard
        status="error"
        retrying={retrying}
        autoRetry={requestState === "scheduled"}
        inSheet={showObservationSheet}
        onRetry={() => {
          if (!retrying) refreshWatch();
        }}
      />
    ) : (
      <StopBoard status="loading" />
    );

  if (showObservationSheet) return <ObservationErrorSheet>{notice}</ObservationErrorSheet>;

  return (
    <main
      className={shared ? styles.sharedNoticePage : styles.noticePage}
      data-station-sheet={stationEntry ?? undefined}
    >
      <h1 className={visuallyHidden}>노선 판정보드</h1>
      <BoardBackLink />
      {notice}
    </main>
  );
}

function ObservationErrorSheet({ children }: { children: ReactNode }) {
  const sheetId = useId();
  const viewport = useBoardViewport();
  const [snap, setSnap] = useState<BoardSheetSnap>("expanded");

  return (
    <main className={styles.sharedPage} style={{ height: viewport.height, top: viewport.top }}>
      <div className={styles.content}>
        <h1 className={visuallyHidden}>노선 판정보드</h1>
        <div className={styles.mapSlot}>{snap !== "full" && <BoardBackLink />}</div>
        <BoardBottomSheet
          id={sheetId}
          snap={snap}
          onSnapChange={setSnap}
          viewportHeight={viewport.height}
          enteringFromMap
          header={snap === "full" && <BoardBackLink inHeader />}
        >
          {children}
        </BoardBottomSheet>
      </div>
    </main>
  );
}

function OutOfServiceSheet({
  board,
  direction,
  shared,
  stationEntry,
  children,
  onGeometryConflict,
}: {
  board: Board;
  direction: Direction;
  shared: boolean;
  stationEntry: StationSheetEntry | null;
  children: ReactNode;
  onGeometryConflict: () => void;
}) {
  const sheetId = useId();
  const viewport = useBoardViewport();
  const [snap, setSnap] = useState<BoardSheetSnap>(stationEntry === "full" ? "full" : "expanded");
  const [motion] = useState(createLiveVehicleMotionStore);
  const mapVisible = snap !== "full";
  const { data, status, retrying } = useRouteMapData(board, mapVisible, onGeometryConflict);
  const shape = useMemo(
    () => (data === null || (data.paths.length === 0 && !data.fullPath?.length) ? null : { ...data, stops: [] }),
    [data],
  );
  const routeName = board.route.displayName;
  return (
    <div className={shared ? styles.sharedPage : styles.page} style={{ height: viewport.height, top: viewport.top }}>
      <main className={styles.content}>
        <div className={styles.mapSlot} inert={!mapVisible} aria-hidden={!mapVisible}>
          {shape !== null && (
            <RouteMap
              board={board}
              data={shape}
              geometryStatus={status}
              geometryRetrying={retrying}
              selected={null}
              onSelect={ignoreMapSelection}
              onVehicleSelect={ignoreMapSelection}
              onDeselect={ignoreMapSelection}
              vehicleFocusRequest={null}
              selectedVehicleKey={null}
              liveVehicleViews={[]}
              motion={motion}
              visible={mapVisible}
              viewportHeight={viewport.height}
              activeDirection={direction}
              quietInitialLoad
            />
          )}
          {mapVisible && <BoardRouteControls routeName={routeName} />}
        </div>
        <BoardBottomSheet
          id={sheetId}
          snap={snap}
          onSnapChange={setSnap}
          viewportHeight={viewport.height}
          enteringFromMap={shared}
          header={snap === "full" && <BoardRouteControls routeName={routeName} inHeader />}
        >
          <div className={styles.centeredNotice}>
            <img className={styles.noticeMascot} src={salmongCrying} width={88} height={88} alt="" />
            {children}
          </div>
        </BoardBottomSheet>
      </main>
    </div>
  );
}

function OperatingBoard({
  stationEntry,
  board,
  direction,
  liveVehicles,
  liveMotionDurationMs,
  switched,
  freshness,
  notice,
  onDirectionChange,
  onStopExpand,
  onGeometryConflict,
}: OperatingBoardProps) {
  const shared = useContext(MapFlowSurfaceContext) !== null;
  const { state: locationState } = useLocation();
  const favoriteMapEntry = shared && boardEntryFrom(locationState) === "favorite";
  const sheetId = useId();
  const viewport = useBoardViewport();
  const [search, setSearch] = useSearchParams();
  const [snap, setSnap] = useState<BoardSheetSnap>(
    stationEntry === "full" ? "full" : stationEntry === "summary" ? "half" : "expanded",
  );
  const [selected, setSelected] = useState<StopSelection | null>(() =>
    stopSelectionFrom(board, direction.id, search.get("stopId")),
  );
  const [selectedFromList, setSelectedFromList] = useState(false);
  const [scrollRequest, setScrollRequest] = useState<StopSelection | null>(selected);
  const [vehicleFocusRequest, setVehicleFocusRequest] = useState<{ key: string; focusMarker: boolean } | null>(null);
  const selectedVehicleKey = vehicleFocusRequest?.key ?? null;
  const sourceStops = useMemo(() => stopViewsFor(board, direction.id), [board, direction.id]);
  const stops = useMemo(() => displayedStopViewsFor(board, direction.id, freshness), [board, direction.id, freshness]);
  const selectedStop =
    selected?.direction === direction.id
      ? sourceStops.find((stop) => stop.sequence === selected.sequence && stop.kind !== "passThrough")
      : undefined;
  const arrivalSelection =
    selectedStop === undefined
      ? null
      : {
          stationId: selectedStop.stopId,
          routeId: board.route.id,
          referenceVersionId: board.route.referenceVersionId,
          stopSequence: selectedStop.sequence,
          direction: direction.id,
        };
  const stationArrivals = useStationArrivals(arrivalSelection);
  const vehicles = useMemo(
    () => liveVehicleViewsFor(board, liveVehicles, direction.id),
    [board, liveVehicles, direction.id],
  );
  const motion = useLiveVehicleMotion(vehicles, liveMotionDurationMs);
  const mapVisible = snap !== "full";
  const map = useRouteMapData(board, mapVisible, onGeometryConflict);
  const mapSelection =
    map.status === "withheld" || (map.status === "generating" && !selectedFromList) ? null : selected;

  function rememberSelection(selection: StopSelection | null) {
    const stop =
      selection === null
        ? undefined
        : board.stops.find(
            (candidate) => candidate.sequence === selection.sequence && candidate.direction === selection.direction,
          );
    setSearch(
      (previous) => {
        if (stop) {
          previous.set("direction", stop.direction);
          previous.set("stopId", stop.stopId);
        } else previous.delete("stopId");
        return previous;
      },
      { replace: true, state: locationState },
    );
  }

  function selectFromMap(selection: StopSelection) {
    const reselected = selected?.direction === selection.direction && selected.sequence === selection.sequence;
    if (!reselected) setSelectedFromList(false);
    setSelected(selection);
    setVehicleFocusRequest(null);
    scrollToStop(selection);
    rememberSelection(selection);
    const row = reselected
      ? null
      : expandableRowOf(displayedStopViewsFor(board, selection.direction, freshness), selection.sequence);
    if (row !== null) onStopExpand({ ...row, direction: selection.direction, source: "map" });
  }

  function scrollToStop(selection: StopSelection) {
    // 같은 위치를 다시 눌러도 목록 이동을 새로 요청한다.
    setScrollRequest({ ...selection });
    if (snap === "minimum") setSnap("expanded");
    onDirectionChange(selection.direction, "map");
  }

  function selectVehicleFromMap(selection: StopSelection, key: string) {
    setVehicleFocusRequest({ key, focusMarker: false });
    scrollToStop(selection);
  }

  function clearMapSelection() {
    setSelected(null);
    rememberSelection(null);
    clearVehicleSelection();
  }

  function clearVehicleSelection() {
    setScrollRequest(null);
    setVehicleFocusRequest(null);
  }

  function focusVehicleFromList(key: string) {
    setScrollRequest(null);
    setVehicleFocusRequest({ key, focusMarker: true });
    if (snap === "full") setSnap("expanded");
  }

  function toggleFromList(sequence: number) {
    setVehicleFocusRequest(null);
    const next =
      selected?.direction === direction.id && selected.sequence === sequence
        ? null
        : { direction: direction.id, sequence };
    setSelected(next);
    setSelectedFromList(next !== null);
    if (next !== null && map.status === "generating" && snap === "full") setSnap("expanded");
    rememberSelection(next);
    setScrollRequest(null);
  }

  function switchDirection(next: Direction) {
    if (next === direction.id) return;
    setSelected(null);
    setScrollRequest(null);
    setVehicleFocusRequest(null);
    onDirectionChange(next, "tab");
  }

  return (
    <div className={shared ? styles.sharedPage : styles.page} style={{ height: viewport.height, top: viewport.top }}>
      <main className={styles.content}>
        <a
          href={`#${sheetId}`}
          className={styles.skipLink}
          onClick={(event) => {
            event.preventDefault();
            document.getElementById(sheetId)?.focus({ preventScroll: true });
          }}
        >
          판정보드로 바로가기
        </a>
        <div className={styles.mapSlot} inert={!mapVisible} aria-hidden={!mapVisible}>
          <RouteMap
            board={board}
            data={map.data}
            geometryStatus={map.status}
            geometryRetrying={map.retrying}
            selected={mapSelection}
            onSelect={selectFromMap}
            onVehicleSelect={selectVehicleFromMap}
            onDeselect={clearMapSelection}
            vehicleFocusRequest={vehicleFocusRequest}
            selectedVehicleKey={selectedVehicleKey}
            liveVehicleViews={vehicles}
            motion={motion}
            visible={mapVisible}
            viewportHeight={viewport.height}
            activeDirection={direction.id}
            preserveViewOnEntry={stationEntry !== null || favoriteMapEntry}
            quietInitialLoad={
              shared && (stationEntry !== null || favoriteMapEntry || quietMapLoadingFrom(locationState))
            }
          />
          <BoardRouteControls routeName={board.route.displayName} />
        </div>
        <BoardBottomSheet
          id={sheetId}
          snap={snap}
          onSnapChange={setSnap}
          viewportHeight={viewport.height}
          enteringFromMap={shared && stationEntry === null}
          header={
            <>
              {snap === "full" && <BoardRouteControls routeName={board.route.displayName} inHeader />}
              <div className={styles.directionHeader}>
                <BoardRouteSummary key={board.route.id} board={board} direction={direction} />
                <DirectionTabs
                  directions={directionViewsFor(board)}
                  selected={direction.id}
                  animated={switched}
                  onSelect={switchDirection}
                />
              </div>
              {notice !== null && (
                <p className={styles.boardNotice} role="status">
                  {boardNoticeLabel(notice)}
                </p>
              )}
            </>
          }
        >
          <StopBoard
            key={direction.id}
            status="ready"
            favoriteRoute={{
              routeId: board.route.id,
              direction: direction.id,
            }}
            stationArrivals={
              arrivalSelection === null ? null : { selection: arrivalSelection, resource: stationArrivals }
            }
            stops={stops}
            liveVehicleViews={vehicles}
            motion={motion}
            entering={switched}
            selectedSequence={selected?.direction === direction.id ? selected.sequence : null}
            scrollRequest={scrollRequest?.direction === direction.id ? scrollRequest : null}
            selectedVehicleKey={selectedVehicleKey}
            onToggle={toggleFromList}
            onStopExpand={(stop, rowPosition) =>
              onStopExpand({ stop, direction: direction.id, rowPosition, source: "list" })
            }
            onVehicleSelect={focusVehicleFromList}
            onVehicleDeselect={clearVehicleSelection}
          />
        </BoardBottomSheet>
      </main>
    </div>
  );
}

function BoardRouteControls({ routeName, inHeader = false }: { routeName: string; inHeader?: boolean }) {
  return (
    <div className={inHeader ? styles.fullControls : styles.mapControls}>
      <BoardBackLink inHeader />
      <h1 className={styles.routeNumber}>{routeName}</h1>
    </div>
  );
}

function BoardBackLink({ inHeader = false }: { inHeader?: boolean }) {
  const location = useLocation();
  const navigate = useNavigate();
  const isFirstPage = location.key === "default";

  return (
    <Link
      to={paths.routeSelect}
      onClick={(event) => {
        if (isFirstPage || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        void navigate(-1);
      }}
      className={inHeader ? styles.fullBack : styles.back}
      data-native-map-hit
      aria-label="노선 선택으로 돌아가기"
    >
      <svg
        width="17"
        height="17"
        viewBox="0 0 17 17"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
      </svg>
    </Link>
  );
}

function boardStateOf(resource: PolledResource<RouteWatch>, preferredDirection: Direction | null): BoardState {
  const latestWatch = latestSuccessOf(resource);
  if (latestWatch !== null) {
    const watch = latestWatch.body;
    return { status: "ready", watch, direction: directionInfoFor(watch, preferredDirection) };
  }
  if (resource.status === "loadingError") {
    return { status: "error", failure: resource.failure };
  }
  return { status: "loading" };
}

// 낡은 판에서 숨기는 것은 차량 마커뿐이라, 운행 판단은 숨기기 전 응답의 차량으로 한다.
function boardScreenOf(state: BoardState): BoardScreen {
  switch (state.status) {
    case "loading":
      return { kind: "loading" };
    case "error":
      return { kind: "error", failure: state.failure };
    case "ready":
      return serviceStateFor(state.watch, state.direction, state.watch.vehicles) === "outOfService"
        ? { kind: "outOfService", board: state.watch }
        : { kind: "forecast", board: state.watch, direction: state.direction.id };
  }
}
