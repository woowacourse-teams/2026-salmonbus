import { useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo, LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { paths } from "@/shared/routing/paths";
import { directionInfoFor, directionViewsFor, serviceStateFor, stopViewsFor, type BoardScreen } from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { useLiveVehicles } from "./useLiveVehicles";
import { useLiveVehicleMotion } from "./hooks/useLiveVehicleMotion";
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
  useBoardVisitEvents({ routeId, screen, latestSuccess: latestSuccessOf(resource) });

  function selectDirection(next: Direction) {
    if (state.status === "ready" && next !== state.direction.id) setSwitched(true);
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
      />
    );
  }

  const status = screen.kind === "outOfService" ? "outOfService" : screen.kind === "error" ? "error" : "loading";
  return (
    <main className={styles.noticePage}>
      <BoardBackLink />
      <StopBoard status={status} />
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
}: OperatingBoardProps) {
  const viewport = useBoardViewport();
  const pageRef = useRef<HTMLDivElement>(null);
  const [snap, setSnap] = useState<BoardSheetSnap>("half");
  const [sheetHeight, setSheetHeight] = useState(() => viewport.height / 2);
  const [selected, setSelected] = useState<StopSelection | null>(null);
  const [selectionRevision, setSelectionRevision] = useState(0);
  const stops = useMemo(() => stopViewsFor(board, direction.id), [board, direction.id]);
  const vehicles = useMemo(
    () => liveVehicleViewsFor(board, liveVehicles, direction.id),
    [board, liveVehicles, direction.id],
  );
  const motion = useLiveVehicleMotion(vehicles, liveMotionDurationMs);
  const mapData = useRouteMapData(board);
  const mapHeight = Math.max(0, viewport.height - sheetHeight);

  function selectFromMap(selection: StopSelection) {
    setSelected(selection);
    setSelectionRevision((current) => current + 1);
    if (snap === "minimum") setSnap("half");
    onDirectionChange(selection.direction);
  }

  function toggleFromList(sequence: number) {
    setSelected((current) =>
      current?.direction === direction.id && current.sequence === sequence
        ? null
        : { direction: direction.id, sequence },
    );
    setSelectionRevision((current) => current + 1);
  }

  function switchDirection(next: Direction) {
    if (next === direction.id) return;
    setSelected(null);
    setSelectionRevision((current) => current + 1);
    onDirectionChange(next);
  }

  return (
    <div ref={pageRef} className={styles.page} style={{ height: viewport.height, top: viewport.top }}>
      <main className={styles.content}>
        <div
          className={styles.mapSlot}
          style={{ height: mapHeight }}
          inert={mapHeight <= 0}
          aria-hidden={mapHeight <= 0}
        >
          <RouteMap
            board={board}
            data={mapData}
            selected={selected}
            onSelect={selectFromMap}
            liveVehicleViews={vehicles}
            motion={motion}
            viewportHeight={mapHeight}
            activeDirection={direction.id}
          />
          <BoardBackLink />
        </div>
        <BoardBottomSheet
          snap={snap}
          onSnapChange={setSnap}
          viewportHeight={viewport.height}
          onHeightChange={setSheetHeight}
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
            stops={stops}
            liveVehicleViews={vehicles}
            motion={motion}
            entering={switched}
            selectedSequence={selected?.direction === direction.id ? selected.sequence : null}
            selectionRevision={selectionRevision}
            onToggle={toggleFromList}
          />
        </BoardBottomSheet>
      </main>
    </div>
  );
}

function BoardBackLink({ inHeader = false }: { inHeader?: boolean }) {
  return (
    <Link
      to={paths.routeSelect}
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
