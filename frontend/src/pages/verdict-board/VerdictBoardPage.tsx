import { useState } from "react";
import { useParams } from "react-router";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import type { Board, Direction, DirectionInfo } from "@/shared/api/routeForecast.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { directionInfoFor, directionViewsFor, serviceStateFor, stopViewsFor } from "./displayPolicy";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

type BoardState =
  | { status: "loading" }
  | { status: "error"; failure: ApiFailure }
  | { status: "ready"; board: Board; direction: DirectionInfo };

function loadBoard(routeId: string, signal: AbortSignal): Promise<ApiResult<Board>> {
  return fetchBoard(routeId, { signal });
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  const { result, body } = usePolledRequest(loadBoard, routeId);
  const [preferredDirection, setPreferredDirection] = useState<Direction | null>(null);
  const [switched, setSwitched] = useState(false);

  const state = boardStateOf(body, result, preferredDirection);

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
        {renderBoard(state, switched)}
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

function renderBoard(state: BoardState, switched: boolean) {
  switch (state.status) {
    case "loading":
      return <StopBoard status="loading" />;
    case "error":
      return <StopBoard status="error" />;
    case "ready":
      return serviceStateFor(state.board, state.direction) === "outOfService" ? (
        <StopBoard status="outOfService" />
      ) : (
        <StopBoard
          key={state.direction.id}
          status="ready"
          stops={stopViewsFor(state.board, state.direction.id)}
          entering={switched}
        />
      );
  }
}
