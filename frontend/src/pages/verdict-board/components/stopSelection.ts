import type { Board, Direction, StopState } from "@/shared/api/routeForecast.types";
import type { BoardSheetSnap } from "@/shared/bottom-sheet/boardSheetPolicy";
import type { ExpandableStopView, StopView } from "./displayPolicy";

export interface StopSelection {
  direction: Direction;
  sequence: StopState["sequence"];
}

export function stopSelectionFrom(board: Board, direction: Direction, stopId: string | null): StopSelection | null {
  const matches = board.stops.filter(
    (stop) => stop.stopId === stopId && stop.direction === direction && stop.boardingAllowed,
  );
  const stop = matches.length === 1 ? matches[0] : undefined;
  return stop === undefined ? null : { direction, sequence: stop.sequence };
}

export function expandableRowOf(
  stops: readonly StopView[],
  sequence: number,
): { stop: ExpandableStopView; rowPosition: number } | null {
  const rowPosition = stops.findIndex((stop) => stop.sequence === sequence);
  const stop = stops[rowPosition];
  return stop === undefined || stop.kind === "passThrough" ? null : { stop, rowPosition };
}

export interface VehicleFocusRequest {
  key: string;
  focusMarker: boolean;
}

export interface BoardSelection {
  selected: StopSelection | null;
  selectedFromList: boolean;
  scrollRequest: StopSelection | null;
  vehicleFocusRequest: VehicleFocusRequest | null;
  snap: BoardSheetSnap;
}

export type BoardSelectionEvent =
  | { type: "mapStopSelected"; stop: StopSelection }
  | { type: "mapVehicleSelected"; stop: StopSelection; vehicleKey: string }
  | { type: "mapCleared" }
  | { type: "listStopToggled"; stop: StopSelection; mapGenerating: boolean }
  | { type: "listVehicleSelected"; vehicleKey: string }
  | { type: "listCleared" }
  | { type: "directionSwitched" };

export function isSameStop(selected: StopSelection | null, stop: StopSelection): boolean {
  return selected?.direction === stop.direction && selected.sequence === stop.sequence;
}

export function nextBoardSelectionFrom(current: BoardSelection, event: BoardSelectionEvent): BoardSelection {
  switch (event.type) {
    case "mapStopSelected":
      return {
        selected: event.stop,
        selectedFromList: isSameStop(current.selected, event.stop) ? current.selectedFromList : false,
        // 같은 위치를 다시 눌러도 목록 이동을 새로 요청한다.
        scrollRequest: { ...event.stop },
        vehicleFocusRequest: null,
        snap: current.snap === "minimum" ? "expanded" : current.snap,
      };
    case "mapVehicleSelected":
      return {
        ...current,
        scrollRequest: { ...event.stop },
        vehicleFocusRequest: { key: event.vehicleKey, focusMarker: false },
        snap: current.snap === "minimum" ? "expanded" : current.snap,
      };
    case "mapCleared":
      return { ...current, selected: null, scrollRequest: null, vehicleFocusRequest: null };
    case "listStopToggled": {
      const selected = isSameStop(current.selected, event.stop) ? null : event.stop;
      return {
        selected,
        selectedFromList: selected !== null,
        scrollRequest: null,
        vehicleFocusRequest: null,
        snap: selected !== null && event.mapGenerating && current.snap === "full" ? "expanded" : current.snap,
      };
    }
    case "listVehicleSelected":
      return {
        ...current,
        scrollRequest: null,
        vehicleFocusRequest: { key: event.vehicleKey, focusMarker: true },
        snap: current.snap === "full" ? "expanded" : current.snap,
      };
    case "listCleared":
      return { ...current, scrollRequest: null, vehicleFocusRequest: null };
    case "directionSwitched":
      return { ...current, selected: null, scrollRequest: null, vehicleFocusRequest: null };
  }
}
