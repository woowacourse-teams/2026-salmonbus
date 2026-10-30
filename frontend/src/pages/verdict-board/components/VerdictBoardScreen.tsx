import { useId, useMemo, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import type { EventMap } from "@/shared/analytics/events";
import { track } from "@/shared/analytics/track";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import { fetchRouteWatch } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo, RouteWatch, Vehicle } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { playHaptic } from "@/shared/haptic/haptic";
import { boardEntryFrom } from "@/shared/routing/boardEntry";
import { paths } from "@/shared/routing/paths";
import { directionInfoFor, directionViewsFor, serviceStateFor, stopViewsFor, type BoardScreen } from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { liveMotionDurationOf, useFreshLiveVehicles } from "../hooks/useFreshLiveVehicles";
import { useLiveVehicleMotion } from "../hooks/useLiveVehicleMotion";
import { useStationArrivals } from "../hooks/useStationArrivals";
import { expandableRowOf, stopSelectionFrom, type StopSelection } from "./stopSelection";
import type { BoardSheetSnap } from "./boardSheetPolicy";
import { BoardBottomSheet } from "./BoardBottomSheet";
import { BoardRouteSummary } from "./BoardRouteSummary";
import { DirectionTabs } from "./DirectionTabs";
import { StopBoard } from "./StopBoard";
import type { StopExpandInput } from "../analytics/boardVisitPolicy";
import { useBoardVisitEvents } from "../analytics/useBoardVisitEvents";
import { RouteMap } from "../map/RouteMap";
import { useRouteMapData } from "../map/useRouteMapData";
import { useBoardViewport } from "../hooks/useBoardViewport";
import { OUT_OF_SERVICE_NOTICE } from "./verdictCopy";
import * as styles from "./VerdictBoardScreen.css";

type BoardState =
  | { status: "loading" }
  | { status: "error"; failure: ApiFailure }
  | { status: "ready"; watch: RouteWatch; direction: DirectionInfo };

type DirectionSwitchSource = EventMap["direction_switched"]["source"];

interface OperatingBoardProps {
  board: Board;
  direction: DirectionInfo;
  liveVehicles: readonly Vehicle[];
  liveMotionDurationMs: number;
  switched: boolean;
  onDirectionChange: (direction: Direction, source: DirectionSwitchSource) => void;
  onStopExpand: (expansion: StopExpandInput) => void;
}

function loadRouteWatch(routeId: string, signal: AbortSignal): Promise<ApiResult<RouteWatch>> {
  return fetchRouteWatch(routeId, { signal });
}

export function VerdictBoardScreen() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  return <RouteBoard key={routeId} routeId={routeId} />;
}

function RouteBoard({ routeId }: { routeId: Board["route"]["id"] }) {
  const [search, setSearch] = useSearchParams();
  const location = useLocation();
  // 방향을 바꾸면 주소를 바꾸면서 이동 state가 사라져서, 처음 그릴 때 읽은 진입 경로를 유지한다.
  const [entry] = useState(() => boardEntryFrom(location.state));
  const watchResource = usePolledRequest(loadRouteWatch, routeId);
  const latestWatch = latestSuccessOf(watchResource);
  const freshLiveVehicles = useFreshLiveVehicles(latestWatch);
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
      { replace: true },
    );
  }

  if (state.status === "ready" && screen.kind === "forecast") {
    return (
      <OperatingBoard
        key={state.watch.route.referenceVersionId}
        board={state.watch}
        direction={state.direction}
        switched={switched}
        liveVehicles={freshLiveVehicles}
        liveMotionDurationMs={motionDurationMs}
        onDirectionChange={selectDirection}
        onStopExpand={trackStopExpanded}
      />
    );
  }

  const status = screen.kind === "outOfService" ? "outOfService" : screen.kind === "error" ? "error" : "loading";
  const runningDirection =
    status === "outOfService" && state.status === "ready"
      ? (state.watch.route.directions.find(
          (candidate) => serviceStateFor(state.watch, candidate, state.watch.vehicles) === "running",
        ) ?? null)
      : null;
  return (
    <main className={styles.noticePage}>
      <h1 className={visuallyHidden}>노선 판정보드</h1>
      <BoardBackLink />
      {status === "outOfService" ? (
        <div className={styles.outOfService}>
          <p role="status">{OUT_OF_SERVICE_NOTICE}</p>
          {runningDirection !== null && (
            <button
              type="button"
              className={styles.alternateDirection}
              onClick={() => selectDirection(runningDirection.id, "outOfService")}
            >
              {runningDirection.terminalStopName} 방면 보기
            </button>
          )}
        </div>
      ) : (
        <StopBoard status={status} />
      )}
    </main>
  );
}

function OperatingBoard({
  board,
  direction,
  liveVehicles,
  liveMotionDurationMs,
  switched,
  onDirectionChange,
  onStopExpand,
}: OperatingBoardProps) {
  const sheetId = useId();
  const viewport = useBoardViewport();
  const [search, setSearch] = useSearchParams();
  const [snap, setSnap] = useState<BoardSheetSnap>("expanded");
  const [selected, setSelected] = useState<StopSelection | null>(() =>
    stopSelectionFrom(board, direction.id, search.get("stopId")),
  );
  const [scrollRequest, setScrollRequest] = useState<StopSelection | null>(selected);
  const [vehicleFocusRequest, setVehicleFocusRequest] = useState<{ key: string; focusMarker: boolean } | null>(null);
  const selectedVehicleKey = vehicleFocusRequest?.key ?? null;
  const stops = useMemo(() => stopViewsFor(board, direction.id), [board, direction.id]);
  const selectedStop =
    selected?.direction === direction.id
      ? stops.find((stop) => stop.sequence === selected.sequence && stop.kind !== "passThrough")
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
  const mapData = useRouteMapData(board);
  const mapVisible = snap !== "full";

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
      { replace: true },
    );
  }

  function selectFromMap(selection: StopSelection) {
    const reselected = selected?.direction === selection.direction && selected.sequence === selection.sequence;
    setSelected(selection);
    setVehicleFocusRequest(null);
    scrollToStop(selection);
    rememberSelection(selection);
    const row = reselected ? null : expandableRowOf(stopViewsFor(board, selection.direction), selection.sequence);
    if (row !== null) onStopExpand({ ...row, direction: selection.direction, source: "map" });
  }

  function scrollToStop(selection: StopSelection) {
    // 같은 위치를 다시 눌러도 목록 이동을 새로 요청한다.
    setScrollRequest({ ...selection });
    if (snap === "minimum") setSnap("half");
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
    if (snap === "full") setSnap("half");
  }

  function toggleFromList(sequence: number) {
    setVehicleFocusRequest(null);
    const next =
      selected?.direction === direction.id && selected.sequence === sequence
        ? null
        : { direction: direction.id, sequence };
    setSelected(next);
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
    <div className={styles.page} style={{ height: viewport.height, top: viewport.top }}>
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
            data={mapData}
            selected={selected}
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
          />
          <BoardBackLink />
        </div>
        <BoardBottomSheet
          id={sheetId}
          snap={snap}
          onSnapChange={setSnap}
          viewportHeight={viewport.height}
          header={
            <>
              {snap === "full" && (
                <div className={styles.fullControls}>
                  <BoardBackLink inHeader />
                </div>
              )}
              <BoardRouteSummary board={board} direction={direction} />
              <DirectionTabs
                directions={directionViewsFor(board)}
                selected={direction.id}
                animated={switched}
                onSelect={switchDirection}
              />
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
