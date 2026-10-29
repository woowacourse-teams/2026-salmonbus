import { useEffect, useRef, useSyncExternalStore } from "react";
import { track } from "@/shared/analytics/track";
import type { LatestSuccess } from "@/shared/api/polledResource";
import type { BoardEntry } from "@/shared/routing/boardEntry";
import type { BoardScreen } from "../components/displayPolicy";
import { nextBoardVisitStep, nextStopExpandStep, type BoardVisit, type StopExpandInput } from "./boardVisitPolicy";

interface Params {
  routeId: string;
  screen: BoardScreen;
  latestSuccess: LatestSuccess<unknown> | null;
  entry: BoardEntry;
}

function subscribeVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

function isDocumentVisible() {
  return document.visibilityState === "visible";
}

export function useBoardVisitEvents({ routeId, screen, latestSuccess, entry }: Params) {
  const visible = useSyncExternalStore(subscribeVisibility, isDocumentVisible);
  const visitRef = useRef<BoardVisit | null>(null);

  useEffect(() => {
    const step = nextBoardVisitStep(visitRef.current, {
      routeId,
      screen,
      receivedAt: latestSuccess?.receivedAt ?? null,
      visible,
      serverNow: latestSuccess?.clock?.now() ?? Date.now(),
      elapsedNow: performance.now(),
      wallNow: Date.now(),
      entry,
    });
    visitRef.current = step.visit;

    if (step.event !== null) {
      track(step.event.name, step.event.properties);
    }
  }, [routeId, screen, latestSuccess, visible, entry]);

  function trackStopExpanded(expansion: StopExpandInput) {
    const step = nextStopExpandStep(visitRef.current, expansion);
    if (step === null) return;
    visitRef.current = step.visit;
    track("stop_expanded", step.properties);
  }

  return { trackStopExpanded };
}
