import type { EventMap } from "@/shared/analytics/events";
import { failurePropertiesOf } from "@/shared/analytics/failureProperties";
import type { ApiFailure } from "@/shared/api/client";
import type { BoardScreen } from "../displayPolicy";
import { boardViewedPropertiesOf } from "./boardEventPolicy";

const NEW_VISIT_AFTER_HIDDEN_MS = 30 * 60 * 1000;

type UnavailableReason = EventMap["board_unavailable"]["reason"];

export interface BoardObservation {
  routeId: string;
  screen: BoardScreen;
  receivedAt: number | null;
  visible: boolean;
  serverNow: number;
  elapsedNow: number;
  wallNow: number;
}

export interface BoardVisit {
  routeId: string;
  startedAt: number;
  staleReceivedAt: number | null;
  staleFailure: ApiFailure | null;
  hiddenSince: number | null;
  viewed: boolean;
  unavailableReasons: readonly UnavailableReason[];
}

export type BoardVisitEvent =
  | { name: "board_viewed"; properties: EventMap["board_viewed"] }
  | { name: "board_unavailable"; properties: EventMap["board_unavailable"] };

export interface BoardVisitStep {
  visit: BoardVisit;
  event: BoardVisitEvent | null;
}

export function nextBoardVisitStep(visit: BoardVisit | null, observation: BoardObservation): BoardVisitStep {
  const current = currentVisitOf(visit, observation);

  if (!observation.visible) {
    return { visit: { ...current, hiddenSince: current.hiddenSince ?? observation.wallNow }, event: null };
  }
  return eventStepOf(current, observation);
}

function currentVisitOf(visit: BoardVisit | null, observation: BoardObservation): BoardVisit {
  if (visit === null || visit.routeId !== observation.routeId) return newVisitOf(observation, null, null);
  if (visit.hiddenSince === null || !observation.visible) return visit;
  if (observation.wallNow - visit.hiddenSince >= NEW_VISIT_AFTER_HIDDEN_MS) {
    return newVisitOf(observation, observation.receivedAt, failureOf(observation.screen));
  }
  return { ...visit, hiddenSince: null };
}

function failureOf(screen: BoardScreen): ApiFailure | null {
  return screen.kind === "error" ? screen.failure : null;
}

function newVisitOf(
  { routeId, elapsedNow }: BoardObservation,
  staleReceivedAt: number | null,
  staleFailure: ApiFailure | null,
): BoardVisit {
  return {
    routeId,
    startedAt: elapsedNow,
    staleReceivedAt,
    staleFailure,
    hiddenSince: null,
    viewed: false,
    unavailableReasons: [],
  };
}

function eventStepOf(visit: BoardVisit, observation: BoardObservation): BoardVisitStep {
  const { screen } = observation;
  const freshForVisit = visit.staleReceivedAt === null || observation.receivedAt !== visit.staleReceivedAt;

  switch (screen.kind) {
    case "loading":
      return { visit, event: null };
    case "forecast": {
      if (visit.viewed || !freshForVisit) return { visit, event: null };
      const properties = boardViewedPropertiesOf(screen.board, screen.direction, {
        now: observation.serverNow,
        loadMs: observation.elapsedNow - visit.startedAt,
      });
      return { visit: { ...visit, viewed: true }, event: { name: "board_viewed", properties } };
    }
    case "outOfService":
      if (!freshForVisit) return { visit, event: null };
      return unavailableStepOf(visit, {
        route_id: visit.routeId,
        reason: "outOfService",
        vehicles_in_service: screen.board.vehiclesInService,
      });
    case "error": {
      if (screen.failure === visit.staleFailure) return { visit, event: null };
      const failureProperties = failurePropertiesOf(screen.failure);
      if (failureProperties === null) return { visit, event: null };
      return unavailableStepOf(visit, { route_id: visit.routeId, reason: "error", ...failureProperties });
    }
  }
}

function unavailableStepOf(visit: BoardVisit, properties: EventMap["board_unavailable"]): BoardVisitStep {
  if (visit.viewed || visit.unavailableReasons.includes(properties.reason)) return { visit, event: null };
  return {
    visit: { ...visit, unavailableReasons: [...visit.unavailableReasons, properties.reason] },
    event: { name: "board_unavailable", properties },
  };
}
