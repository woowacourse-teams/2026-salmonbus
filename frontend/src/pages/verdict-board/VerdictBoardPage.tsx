import { useState } from "react";
import { useParams } from "react-router";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import { boardMock, liveVehiclesMock } from "@/shared/api/routeForecast.mock";
import type { Board, Direction, LiveVehicles } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { directionViewsFor, serviceStateFor, stopViewsFor } from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { DEFAULT_LIVE_MOTION_DURATION_MS, useLiveVehicles } from "./useLiveVehicles";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

type BoardState = { status: "loading" } | { status: "error"; failure: ApiFailure } | { status: "ready"; board: Board };

const USE_ROUTE_MOCKS = false;

function loadBoard(routeId: string, signal: AbortSignal): Promise<ApiResult<Board>> {
  return fetchBoard(routeId, { signal });
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  const { result: boardResult, body: boardBody } = usePolledRequest(loadBoard, routeId);
  const liveVehicleState = useLiveVehicles(routeId);
  const [direction, setDirection] = useState<Direction>("UP");
  const [switched, setSwitched] = useState(false);

  const state: BoardState = USE_ROUTE_MOCKS
    ? { status: "ready", board: boardMock }
    : boardStateOf(boardBody, boardResult);
  const liveVehicles = USE_ROUTE_MOCKS ? liveVehiclesMock : liveVehicleState.liveVehicles;
  const liveMotionDurationMs = USE_ROUTE_MOCKS ? DEFAULT_LIVE_MOTION_DURATION_MS : liveVehicleState.motionDurationMs;

  function selectDirection(next: Direction) {
    if (next !== direction) {
      setSwitched(true);
    }
    setDirection(next);
  }

  return (
    <div className={styles.page}>
      <main className={styles.content}>
        <div className={styles.controls}>
          <BoardHeader routeName={state.status === "ready" ? state.board.route.displayName : ""} />
          {state.status === "ready" && (
            <DirectionTabs
              directions={directionViewsFor(state.board)}
              selected={direction}
              animated={switched}
              onSelect={selectDirection}
            />
          )}
        </div>
        {renderBoard(state, direction, switched, liveVehicles, liveMotionDurationMs)}
      </main>
    </div>
  );
}

function boardStateOf(board: Board | null, result: ApiResult<Board> | null): BoardState {
  if (board !== null) {
    return { status: "ready", board };
  }
  if (result !== null && !result.ok) {
    return { status: "error", failure: result.failure };
  }
  return { status: "loading" };
}

function renderBoard(
  state: BoardState,
  direction: Direction,
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
      if (serviceStateFor(state.board, direction) === "outOfService") {
        return <StopBoard status="outOfService" />;
      }

      return (
        <StopBoard
          key={direction}
          status="ready"
          stops={stopViewsFor(state.board, direction)}
          liveVehicleViews={liveVehicleViewsFor(state.board, liveVehicles, direction)}
          liveMotionDurationMs={liveMotionDurationMs}
          entering={switched}
        />
      );
    }
  }
}
