import { useEffect, useState } from "react";
import { useParams } from "react-router";
import type { ApiFailure } from "@/shared/api/client";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchBoard } from "@/shared/api/routeForecast.api";
import type { Board, Direction } from "@/shared/api/routeForecast.types";
import { directionViewsFor, stopViewsFor } from "./displayPolicy";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

type BoardOutcome = { status: "error"; failure: ApiFailure } | { status: "ready"; board: Board };
type BoardState = { status: "loading" } | BoardOutcome;

interface LoadedBoard {
  routeId: string;
  outcome: BoardOutcome;
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  const [gate] = useState(createLatestRequestGate);
  const [loaded, setLoaded] = useState<LoadedBoard | null>(null);
  const [direction, setDirection] = useState<Direction>("UP");

  useEffect(() => {
    const ticket = gate.issue();
    void fetchBoard(routeId, { signal: ticket.signal }).then((result) => {
      if (!ticket.isLatest() || (!result.ok && result.failure.kind === "aborted")) {
        return;
      }
      const outcome: BoardOutcome = result.ok
        ? { status: "ready", board: result.body }
        : { status: "error", failure: result.failure };
      setLoaded({ routeId, outcome });
    });
    return () => ticket.abort();
  }, [gate, routeId]);

  const state: BoardState = loaded?.routeId === routeId ? loaded.outcome : { status: "loading" };

  return (
    <div className={styles.page}>
      <main className={styles.content}>
        <BoardHeader routeName={state.status === "ready" ? state.board.route.displayName : ""} />
        {state.status === "ready" && (
          <DirectionTabs directions={directionViewsFor(state.board)} selected={direction} onSelect={setDirection} />
        )}
        {renderBoard(state, direction)}
      </main>
    </div>
  );
}

function renderBoard(state: BoardState, direction: Direction) {
  switch (state.status) {
    case "loading":
      return <StopBoard status="loading" />;
    case "error":
      return <StopBoard status="error" />;
    case "ready":
      return <StopBoard status="ready" stops={stopViewsFor(state.board, direction)} />;
  }
}
