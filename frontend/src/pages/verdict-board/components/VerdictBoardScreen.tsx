import { useMemo, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import { track } from "@/shared/analytics/track";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import { fetchRouteWatch } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo, RouteWatch, Vehicle } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { paths } from "@/shared/routing/paths";
import {
  directionInfoFor,
  directionViewsFor,
  serviceStateFor,
  stopViewsFor,
  type BoardScreen,
  type ExpandableStopView,
} from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { liveMotionDurationOf, useFreshLiveVehicles } from "../hooks/useFreshLiveVehicles";
import { useLiveVehicleMotion } from "../hooks/useLiveVehicleMotion";
import { useStationArrivals } from "../hooks/useStationArrivals";
import { stopSelectionFrom, type StopSelection } from "./stopSelection";
import type { BoardSheetSnap } from "./boardSheetPolicy";
import { BoardBottomSheet } from "./BoardBottomSheet";
import { BoardRouteSummary } from "./BoardRouteSummary";
import { DirectionTabs } from "./DirectionTabs";
import { StopBoard } from "./StopBoard";
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

interface OperatingBoardProps {
  board: Board;
  direction: DirectionInfo;
  liveVehicles: readonly Vehicle[];
  liveMotionDurationMs: number;
  switched: boolean;
  onDirectionChange: (direction: Direction) => void;
  onStopExpand: (stop: ExpandableStopView, rowPosition: number) => void;
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
  const { trackStopExpanded } = useBoardVisitEvents({ routeId, screen, latestSuccess: latestWatch });

  function selectDirection(next: Direction) {
    setPreferredDirection(next);
    if (state.status === "ready" && next !== state.direction.id) {
      setSwitched(true);
      track("direction_switched", { route_id: routeId, to_direction: next });
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
        onStopExpand={(stop, rowPosition) => trackStopExpanded(stop, state.direction.id, rowPosition)}
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
      <BoardBackLink />
      {status === "outOfService" ? (
        <div className={styles.outOfService}>
          <p role="status">{OUT_OF_SERVICE_NOTICE}</p>
          {runningDirection !== null && (
            <button
              type="button"
              className={styles.alternateDirection}
              onClick={() => selectDirection(runningDirection.id)}
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
    setSelected(selection);
    setVehicleFocusRequest(null);
    scrollToStop(selection);
    rememberSelection(selection);
  }

  function scrollToStop(selection: StopSelection) {
    // 같은 위치를 다시 눌러도 목록 이동을 새로 요청한다.
    setScrollRequest({ ...selection });
    if (snap === "minimum") setSnap("half");
    onDirectionChange(selection.direction);
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
    onDirectionChange(next);
  }

  return (
    <div className={styles.page} style={{ height: viewport.height, top: viewport.top }}>
      <main className={styles.content}>
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
            onStopExpand={onStopExpand}
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
