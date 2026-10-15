import { useEffect, useState } from "react";
import type { PolledResource } from "@/shared/api/polledResource";
import type { Board, LiveVehicles } from "@/shared/api/routeForecast.types";
import {
  boardHistoriesWith,
  boardSnapshotFor,
  emptyBoardHistoriesFor,
  type BoardSnapshot,
} from "./boardSnapshotPolicy";

export function useBoardSnapshot(
  routeId: string,
  boardResource: PolledResource<Board>,
  liveVehicleResource: PolledResource<LiveVehicles>,
): BoardSnapshot | null {
  const [histories, setHistories] = useState(() => emptyBoardHistoriesFor(routeId));
  const [now, setNow] = useState(() => Date.now());

  const nextHistories = boardHistoriesWith(histories, routeId, boardResource, liveVehicleResource);
  if (nextHistories !== histories) {
    setHistories(nextHistories);
  }

  const { snapshot, recheckAt } = boardSnapshotFor(nextHistories.boards, nextHistories.liveVehicles, now);

  useEffect(() => {
    if (recheckAt === null) return;

    const timer = window.setTimeout(() => setNow(Math.max(Date.now(), recheckAt)), Math.max(0, recheckAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [recheckAt]);

  return snapshot;
}
