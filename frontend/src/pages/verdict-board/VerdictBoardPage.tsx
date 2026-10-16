import { useMemo, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { track } from "@/shared/analytics/track";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo, LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { paths } from "@/shared/routing/paths";
import {
  directionInfoFor,
  directionViewsFor,
  serviceStateFor,
  stopViewsFor,
  type BoardScreen,
  type ExpandableStopView,
} from "./components/displayPolicy";
import { liveVehicleViewsFor } from "./components/liveVehiclePolicy";
import { useLiveVehicles } from "./hooks/useLiveVehicles";
import { useLiveVehicleMotion } from "./hooks/useLiveVehicleMotion";
import { useStationArrivals } from "./hooks/useStationArrivals";
import type { StopSelection } from "./components/stopSelection";
import type { BoardSheetSnap } from "./components/boardSheetPolicy";
import { BoardBottomSheet } from "./components/BoardBottomSheet";
import { BoardRouteSummary } from "./components/BoardRouteSummary";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import { useBoardVisitEvents } from "./analytics/useBoardVisitEvents";
import { RouteMap } from "./map/RouteMap";
import { useRouteMapData } from "./map/useRouteMapData";
import { useBoardViewport } from "./hooks/useBoardViewport";
import { useBoardOverlayAnchors } from "./hooks/useBoardOverlayAnchors";
import { OUT_OF_SERVICE_NOTICE } from "./components/verdictCopy";
import * as styles from "./VerdictBoardPage.css";

type BoardState =
  | { status: "loading" }
  | { status: "error"; failure: ApiFailure }
  | { status: "ready"; board: Board; direction: DirectionInfo };

interface OperatingBoardProps {
  board: Board;
  direction: DirectionInfo;
  liveVehicles: LiveVehicles | null;
  liveMotionDurationMs: number;
  switched: boolean;
  onDirectionChange: (direction: Direction) => void;
  onStopExpand: (stop: ExpandableStopView, rowPosition: number) => void;
}

function loadBoard(routeId: string, signal: AbortSignal): Promise<ApiResult<Board>> {
  return fetchBoard(routeId, { signal });
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  return <RouteBoard key={routeId} routeId={routeId} />;
}

function RouteBoard({ routeId }: { routeId: Board["route"]["id"] }) {
  const resource = usePolledRequest(loadBoard, routeId);
  const liveVehicleState = useLiveVehicles(routeId);
  const [preferredDirection, setPreferredDirection] = useState<Direction | null>(null);
  const [switched, setSwitched] = useState(false);
  const state = boardStateOf(resource, preferredDirection);
  const screen = boardScreenOf(state);
  const { trackStopExpanded } = useBoardVisitEvents({ routeId, screen, latestSuccess: latestSuccessOf(resource) });

  function selectDirection(next: Direction) {
    if (state.status === "ready" && next !== state.direction.id) {
      setSwitched(true);
      track("direction_switched", { route_id: routeId, to_direction: next });
    }
    setPreferredDirection(next);
  }

  if (state.status === "ready" && screen.kind === "forecast") {
    return (
      <OperatingBoard
        key={state.board.route.referenceVersionId}
        board={state.board}
        direction={state.direction}
        switched={switched}
        liveVehicles={liveVehicleState.liveVehicles}
        liveMotionDurationMs={liveVehicleState.motionDurationMs}
        onDirectionChange={selectDirection}
        onStopExpand={(stop, rowPosition) => trackStopExpanded(stop, state.direction.id, rowPosition)}
      />
    );
  }

  const status = screen.kind === "outOfService" ? "outOfService" : screen.kind === "error" ? "error" : "loading";
  const runningDirection =
    status === "outOfService" && state.status === "ready"
      ? (state.board.route.directions.find((candidate) => serviceStateFor(state.board, candidate) === "running") ??
        null)
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
  const pageRef = useRef<HTMLDivElement>(null);
  const [snap, setSnap] = useState<BoardSheetSnap>("half");
  const [selected, setSelected] = useState<StopSelection | null>(null);
  const [scrollRequest, setScrollRequest] = useState<StopSelection | null>(null);
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
  useBoardOverlayAnchors(pageRef, snap, viewport.height);

  function selectFromMap(selection: StopSelection) {
    setSelected(selection);
    setVehicleFocusRequest(null);
    scrollToStop(selection);
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

  function focusVehicleFromList(key: string) {
    setScrollRequest(null);
    setVehicleFocusRequest({ key, focusMarker: true });
    if (snap === "full") setSnap("half");
  }

  function toggleFromList(sequence: number) {
    setVehicleFocusRequest(null);
    setSelected((current) =>
      current?.direction === direction.id && current.sequence === sequence
        ? null
        : { direction: direction.id, sequence },
    );
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
    <div ref={pageRef} className={styles.page} style={{ height: viewport.height, top: viewport.top }}>
      <main className={styles.content}>
        <div className={styles.mapSlot} inert={!mapVisible} aria-hidden={!mapVisible}>
          <RouteMap
            board={board}
            data={mapData}
            selected={selected}
            onSelect={selectFromMap}
            onVehicleSelect={selectVehicleFromMap}
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
              <BoardRouteSummary
                board={board}
                direction={direction}
                headwayLabel={__BOARD_MAP_PREVIEW__ ? "15–25분" : "정보 준비 중"}
              />
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

function boardStateOf(resource: PolledResource<Board>, preferredDirection: Direction | null): BoardState {
  switch (resource.status) {
    case "pending":
      return { status: "loading" };
    case "loadingError":
      return { status: "error", failure: resource.failure };
    case "success":
    case "refetchError": {
      const board = resource.latestSuccess.body;
      return { status: "ready", board, direction: directionInfoFor(board, preferredDirection) };
    }
  }
}

function boardScreenOf(state: BoardState): BoardScreen {
  switch (state.status) {
    case "loading":
      return { kind: "loading" };
    case "error":
      return { kind: "error", failure: state.failure };
    case "ready":
      return serviceStateFor(state.board, state.direction) === "outOfService"
        ? { kind: "outOfService", board: state.board }
        : { kind: "forecast", board: state.board, direction: state.direction.id };
  }
}
