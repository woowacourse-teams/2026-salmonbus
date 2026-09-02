import type { Board, Direction, LiveVehicles, Phase, Vehicle } from "@/shared/api/routeForecast.types";

export interface LiveVehicleMotionSegment {
  fromStopSequence: number;
  toStopSequence: number;
  startProgress: number;
  endProgress: number;
}

export interface LiveVehicleView {
  key: string;
  vehicle: Vehicle;
  motionSegment: LiveVehicleMotionSegment;
}

interface PhaseMotionRule {
  neighbor: "previous" | "next";
  startProgress: number;
  endProgress: number;
}

const PHASE_RANK: Record<Phase, number> = {
  ARRIVING: 0,
  DEPARTED: 1,
  IN_TRANSIT: 2,
};

const PHASE_MOTION_RULE: Record<Phase, PhaseMotionRule> = {
  ARRIVING: { neighbor: "previous", startProgress: 0.55, endProgress: 0.9 },
  DEPARTED: { neighbor: "next", startProgress: 0.08, endProgress: 0.3 },
  IN_TRANSIT: { neighbor: "next", startProgress: 0.35, endProgress: 0.8 },
};

export function liveVehicleViewsFor(
  board: Board,
  liveVehicles: LiveVehicles | null | undefined,
  direction: Direction,
): LiveVehicleView[] {
  if (!canDisplayLiveVehicles(board, liveVehicles)) return [];

  const stopSequences = board.stops
    .filter((stop) => stop.direction === direction)
    .map((stop) => stop.sequence)
    .sort((left, right) => left - right);
  const stopSequenceSet = new Set(stopSequences);

  const visibleVehicles = liveVehicles.vehicles
    .filter((vehicle) => vehicle.direction === direction && stopSequenceSet.has(vehicle.currentStopSequence))
    .sort(compareVehicles);
  const occurrenceByIdentity = new Map<string, number>();

  return visibleVehicles.map((vehicle) => ({
    key: stableVehicleKey(vehicle, occurrenceByIdentity),
    vehicle,
    motionSegment: motionSegmentFor(vehicle, stopSequences),
  }));
}

function canDisplayLiveVehicles(
  board: Board,
  liveVehicles: LiveVehicles | null | undefined,
): liveVehicles is LiveVehicles {
  return (
    liveVehicles !== null &&
    liveVehicles !== undefined &&
    liveVehicles.observation.state === "VEHICLES_PRESENT" &&
    liveVehicles.routeId === board.route.id &&
    liveVehicles.referenceVersionId === board.route.referenceVersionId
  );
}

function compareVehicles(left: Vehicle, right: Vehicle): number {
  const sequenceDifference = left.currentStopSequence - right.currentStopSequence;
  if (sequenceDifference !== 0) return sequenceDifference;

  const phaseDifference = PHASE_RANK[left.phase] - PHASE_RANK[right.phase];
  if (phaseDifference !== 0) return phaseDifference;

  return compareVehicleIds(left.vehicleId, right.vehicleId);
}

function compareVehicleIds(left: string | null, right: string | null): number {
  const sortableLeft = left ?? "";
  const sortableRight = right ?? "";
  if (sortableLeft < sortableRight) return -1;
  if (sortableLeft > sortableRight) return 1;
  return 0;
}

function motionSegmentFor(vehicle: Vehicle, stopSequences: number[]): LiveVehicleMotionSegment {
  const currentStopIndex = stopSequences.indexOf(vehicle.currentStopSequence);
  const currentStopSequence = vehicle.currentStopSequence;
  const rule = PHASE_MOTION_RULE[vehicle.phase];
  const neighborIndex = rule.neighbor === "previous" ? currentStopIndex - 1 : currentStopIndex + 1;
  const neighborStopSequence = stopSequences[neighborIndex];

  if (neighborStopSequence === undefined) {
    return {
      fromStopSequence: currentStopSequence,
      toStopSequence: currentStopSequence,
      startProgress: 0,
      endProgress: 0,
    };
  }

  return {
    fromStopSequence: rule.neighbor === "previous" ? neighborStopSequence : currentStopSequence,
    toStopSequence: rule.neighbor === "previous" ? currentStopSequence : neighborStopSequence,
    startProgress: rule.startProgress,
    endProgress: rule.endProgress,
  };
}

function stableVehicleKey(vehicle: Vehicle, occurrenceByIdentity: Map<string, number>): string {
  const identity =
    vehicle.vehicleId === null
      ? JSON.stringify([
          "anonymous-vehicle",
          vehicle.direction,
          vehicle.currentStopSequence,
          vehicle.phase,
          vehicle.stopId,
        ])
      : JSON.stringify(["vehicle", vehicle.vehicleId]);
  const occurrence = (occurrenceByIdentity.get(identity) ?? 0) + 1;
  occurrenceByIdentity.set(identity, occurrence);

  return `${identity}#${occurrence}`;
}
