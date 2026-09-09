import { useState } from "react";
import { useParams } from "react-router";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import { boardMock, liveVehiclesMock } from "@/shared/api/routeForecast.mock";
import type { Board, Direction, DirectionInfo, LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { directionInfoFor, directionViewsFor, serviceStateFor, stopViewsFor } from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { DEFAULT_LIVE_MOTION_DURATION_MS, useLiveVehicles } from "./useLiveVehicles";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

type BoardState =
  | { status: "loading" }
  | { status: "error"; failure: ApiFailure }
  | { status: "ready"; board: Board; direction: DirectionInfo };

const USE_ROUTE_MOCKS = false;

function loadBoard(routeId: string, signal: AbortSignal): Promise<ApiResult<Board>> {
  return fetchBoard(routeId, { signal });
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  const { result: boardResult, body: boardBody } = usePolledRequest(loadBoard, routeId);
  const liveVehicleState = useLiveVehicles(routeId);
  const [preferredDirection, setPreferredDirection] = useState<Direction | null>(null);
  const [switched, setSwitched] = useState(false);

  const state: BoardState = USE_ROUTE_MOCKS
    ? boardStateOf(boardMock, boardResult, preferredDirection)
    : boardStateOf(boardBody, boardResult, preferredDirection);
  const liveVehicles = USE_ROUTE_MOCKS ? liveVehiclesMock : liveVehicleState.liveVehicles;
  const liveMotionDurationMs = USE_ROUTE_MOCKS ? DEFAULT_LIVE_MOTION_DURATION_MS : liveVehicleState.motionDurationMs;

  function selectDirection(next: Direction) {
    if (state.status === "ready" && next !== state.direction.id) {
      setSwitched(true);
    }
    setPreferredDirection(next);
  }

  return (
    <div className={styles.page}>
      <main className={styles.content}>
        <div className={styles.controls}>
          <BoardHeader routeName={state.status === "ready" ? state.board.route.displayName : ""} />
          {state.status === "ready" && (
            <DirectionTabs
              directions={directionViewsFor(state.board)}
              selected={state.direction.id}
              animated={switched}
              onSelect={selectDirection}
            />
          )}
        </div>
        {renderBoard(state, switched, liveVehicles, liveMotionDurationMs)}
      </main>
    </div>
  );
}

function boardStateOf(
  board: Board | null,
  result: ApiResult<Board> | null,
  preferredDirection: Direction | null,
): BoardState {
  if (board !== null) {
    return { status: "ready", board, direction: directionInfoFor(board, preferredDirection) };
  }
  if (result !== null && !result.ok) {
    return { status: "error", failure: result.failure };
  }
  return { status: "loading" };
}

function renderBoard(
  state: BoardState,
  switched: boolean,
  liveVehicles: LiveVehicles | null,
  liveMotionDurationMs: number,
) {
  switch (state.status) {
    case "loading":
      return <StopBoard status="loading" />;
    case "error":
      return <StopBoard status="error" />;
    case "ready": {
      if (serviceStateFor(state.board, state.direction) === "outOfService") {
        return <StopBoard status="outOfService" />;
      }

      return (
        <StopBoard
          key={state.direction.id}
          status="ready"
          stops={stopViewsFor(state.board, state.direction.id)}
          liveVehicleViews={liveVehicleViewsFor(state.board, liveVehicles, state.direction.id)}
          liveMotionDurationMs={liveMotionDurationMs}
          entering={switched}
        />
      );
    }
  }
}
