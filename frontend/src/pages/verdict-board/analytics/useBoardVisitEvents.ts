import { useEffect, useRef, useSyncExternalStore } from "react";
import { track } from "@/shared/analytics/track";
import type { ReferenceClock } from "@/shared/api/referenceClock";
import type { BoardScreen } from "../displayPolicy";
import { nextBoardVisitStep, type BoardVisit } from "./boardVisitPolicy";

interface Params {
  routeId: string;
  screen: BoardScreen;
  receivedAt: number | null;
  clock: ReferenceClock | null;
}

function subscribeVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

function isDocumentVisible() {
  return document.visibilityState === "visible";
}

export function useBoardVisitEvents({ routeId, screen, receivedAt, clock }: Params) {
  const visible = useSyncExternalStore(subscribeVisibility, isDocumentVisible);
  const visitRef = useRef<BoardVisit | null>(null);

  useEffect(() => {
    const step = nextBoardVisitStep(visitRef.current, {
      routeId,
      screen,
      receivedAt,
      visible,
      serverNow: clock?.now() ?? Date.now(),
      elapsedNow: performance.now(),
      wallNow: Date.now(),
    });
    visitRef.current = step.visit;

    if (step.event !== null) {
      track(step.event.name, step.event.properties);
    }
  }, [routeId, screen, receivedAt, clock, visible]);
}
