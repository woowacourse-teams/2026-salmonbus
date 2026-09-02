import { useEffect, useState } from "react";
import { useParams } from "react-router";
import type { ApiFailure, ApiResult } from "@/shared/api/client";
import { fetchBoard, fetchLiveVehicles } from "@/shared/api/routeForecast.api";
import { boardMock, liveVehiclesMock } from "@/shared/api/routeForecast.mock";
import type { Board, Direction, LiveVehicles } from "@/shared/api/routeForecast.types";
import type { ReferenceClock } from "@/shared/api/referenceClock";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { directionViewsFor, serviceStateFor, stopViewsFor } from "./displayPolicy";
import { liveVehicleViewsFor } from "./liveVehiclePolicy";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

type BoardState = { status: "loading" } | { status: "error"; failure: ApiFailure } | { status: "ready"; board: Board };

const USE_ROUTE_MOCKS = false;
const DEFAULT_LIVE_MOTION_DURATION_MS = 20_000;
const MIN_LIVE_MOTION_DURATION_MS = 5_000;

function loadBoard(routeId: string, signal: AbortSignal): Promise<ApiResult<Board>> {
  return fetchBoard(routeId, { signal });
}

function loadLiveVehicles(routeId: string, signal: AbortSignal): Promise<ApiResult<LiveVehicles>> {
  return fetchLiveVehicles(routeId, { signal });
}

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  const { result: boardResult, body: boardBody } = usePolledRequest(loadBoard, routeId);
  const {
    result: liveVehicleResult,
    body: liveVehicleBody,
    clock: liveVehicleClock,
    receivedAt: liveVehicleReceivedAt,
  } = usePolledRequest(loadLiveVehicles, routeId);
  const freshLiveVehicleBody = useFreshLiveVehicles(liveVehicleBody, liveVehicleClock, liveVehicleReceivedAt);
  const [direction, setDirection] = useState<Direction>("UP");
  const [switched, setSwitched] = useState(false);

  const state: BoardState = USE_ROUTE_MOCKS
    ? { status: "ready", board: boardMock }
    : boardStateOf(boardBody, boardResult);
  const liveVehicles = USE_ROUTE_MOCKS ? liveVehiclesMock : freshLiveVehicleBody;
  const liveMotionDurationMs = USE_ROUTE_MOCKS
    ? DEFAULT_LIVE_MOTION_DURATION_MS
    : liveMotionDurationOf(liveVehicleResult);

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

function useFreshLiveVehicles(
  body: LiveVehicles | null,
  serverClock: ReferenceClock | null,
  receivedAt: number | null,
): LiveVehicles | null {
  const staleAt = body?.observation.staleAt ?? null;
  const staleAtMillis = staleAt === null ? null : Date.parse(staleAt);
  const observationKey =
    body === null || staleAt === null ? null : JSON.stringify([body.routeId, body.referenceVersionId, staleAt]);
  const [expiredObservationKey, setExpiredObservationKey] = useState<string | null>(null);

  useEffect(() => {
    if (observationKey === null || staleAtMillis === null) return;

    const referenceNow = serverClock?.now() ?? Date.now();
    const expiresInMs = Number.isNaN(staleAtMillis) ? 0 : Math.max(0, staleAtMillis - referenceNow);
    const expiryTimer = window.setTimeout(() => setExpiredObservationKey(observationKey), expiresInMs);

    return () => window.clearTimeout(expiryTimer);
  }, [observationKey, serverClock, staleAtMillis]);

  const referenceAtReceipt = serverClock?.servedAt ?? receivedAt;
  const expiredBeforeReceipt =
    staleAtMillis !== null &&
    (Number.isNaN(staleAtMillis) || (referenceAtReceipt !== null && staleAtMillis <= referenceAtReceipt));

  return body !== null && observationKey !== null && !expiredBeforeReceipt && expiredObservationKey !== observationKey
    ? body
    : null;
}

function liveMotionDurationOf(result: ApiResult<LiveVehicles> | null): number {
  if (result === null || !result.ok || result.lifetime.noStore || result.lifetime.maxAgeSeconds === null) {
    return DEFAULT_LIVE_MOTION_DURATION_MS;
  }

  const remainingFreshSeconds = Math.max(0, result.lifetime.maxAgeSeconds - result.lifetime.ageSeconds);
  return Math.max(MIN_LIVE_MOTION_DURATION_MS, remainingFreshSeconds * 1_000);
}
