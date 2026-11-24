import { describe, expect, it } from "@jest/globals";
import {
  expandableRowOf,
  nextBoardSelection,
  stopSelectionFrom,
  type BoardSelection,
  type StopSelection,
} from "./stopSelection";
import type { StopView } from "./displayPolicy";
import type { Board } from "@/shared/api/routeForecast.types";
import { boardMock } from "@/shared/api/routeForecast.mock";

describe("로그인 후 정류장 복귀", () => {
  it("현재 방면에 승차 가능한 방문 하나가 있을 때만 복원한다", () => {
    const stop = boardMock.stops.find((candidate) => candidate.boardingAllowed)!;
    expect(stopSelectionFrom(boardMock, stop.direction, stop.stopId)).toEqual({
      direction: stop.direction,
      sequence: stop.sequence,
    });
    expect(stopSelectionFrom(boardMock, stop.direction, "deleted-stop")).toBeNull();
  });
  it("같은 방면의 중복 방문은 임의로 선택하지 않는다", () => {
    const stop = boardMock.stops.find((candidate) => candidate.boardingAllowed)!;
    const board: Board = { ...boardMock, stops: [stop, { ...stop, sequence: stop.sequence + 1 }] };
    expect(stopSelectionFrom(board, stop.direction, stop.stopId)).toBeNull();
  });
});

describe("지도에서 고른 정류장의 목록 행", () => {
  const stops: StopView[] = [
    { kind: "passThrough", sequence: 1, stopId: "S1", name: "경유 정류장", isTurnaround: false },
    { kind: "noVehicle", sequence: 2, stopId: "S2", name: "정류장2", isTurnaround: false },
  ];

  it("경유 정류장 행까지 센 목록 위치와 펼칠 정류장을 찾는다", () => {
    expect(expandableRowOf(stops, 2)).toEqual({ stop: stops[1], rowPosition: 1 });
  });

  it("경유 정류장이나 목록에 없는 정류장은 펼침으로 세지 않는다", () => {
    expect(expandableRowOf(stops, 1)).toBeNull();
    expect(expandableRowOf(stops, 9)).toBeNull();
  });
});

describe("판정 보드의 선택 전이", () => {
  const stop8: StopSelection = { direction: "UP", sequence: 8 };
  const stop11: StopSelection = { direction: "UP", sequence: 11 };
  const nothing: BoardSelection = {
    selected: null,
    selectedFromList: false,
    scrollRequest: null,
    vehicleFocusRequest: null,
    snap: "expanded",
  };
  const stopExpandedInList: BoardSelection = { ...nothing, selected: stop11, selectedFromList: true };
  const busSelected: BoardSelection = {
    ...stopExpandedInList,
    scrollRequest: stop8,
    vehicleFocusRequest: { key: "bus-1", focusMarker: false },
  };

  describe("목록에서 정류장을 누르면", () => {
    it("그 정류장을 펼치고 목록에서 골랐다고 적으며 목록을 옮기지 않는다", () => {
      expect(nextBoardSelection(nothing, { type: "listStopToggled", stop: stop11, mapGenerating: false })).toEqual({
        ...nothing,
        selected: stop11,
        selectedFromList: true,
      });
    });

    it("펼친 정류장을 다시 누르면 접는다", () => {
      expect(
        nextBoardSelection(stopExpandedInList, { type: "listStopToggled", stop: stop11, mapGenerating: false }),
      ).toEqual(nothing);
    });

    it("다른 정류장을 펼치면 버스 선택과 목록 이동 요청을 푼다", () => {
      expect(nextBoardSelection(busSelected, { type: "listStopToggled", stop: stop8, mapGenerating: false })).toEqual({
        ...nothing,
        selected: stop8,
        selectedFromList: true,
      });
    });

    it("노선을 만드는 중이고 시트가 전체 높이면 60% 높이로 내린다", () => {
      const full: BoardSelection = { ...nothing, snap: "full" };

      expect(nextBoardSelection(full, { type: "listStopToggled", stop: stop11, mapGenerating: true }).snap).toBe(
        "expanded",
      );
      expect(nextBoardSelection(full, { type: "listStopToggled", stop: stop11, mapGenerating: false }).snap).toBe(
        "full",
      );
      expect(
        nextBoardSelection(
          { ...stopExpandedInList, snap: "full" },
          { type: "listStopToggled", stop: stop11, mapGenerating: true },
        ).snap,
      ).toBe("full");
    });
  });

  describe("목록에서 버스를 누르면", () => {
    it("펼친 정류장은 그대로 두고 지도 마커에 포커스를 요청하며 목록을 옮기지 않는다", () => {
      expect(nextBoardSelection(busSelected, { type: "listVehicleSelected", vehicleKey: "bus-2" })).toEqual({
        ...stopExpandedInList,
        vehicleFocusRequest: { key: "bus-2", focusMarker: true },
      });
    });

    it("같은 버스를 다시 눌러도 새 요청으로 만든다", () => {
      const first = nextBoardSelection(nothing, { type: "listVehicleSelected", vehicleKey: "bus-1" });
      const second = nextBoardSelection(first, { type: "listVehicleSelected", vehicleKey: "bus-1" });

      expect(second.vehicleFocusRequest).toEqual(first.vehicleFocusRequest);
      expect(second.vehicleFocusRequest).not.toBe(first.vehicleFocusRequest);
    });

    it("시트가 전체 높이면 60% 높이로 내린다", () => {
      expect(
        nextBoardSelection({ ...nothing, snap: "full" }, { type: "listVehicleSelected", vehicleKey: "bus-1" }).snap,
      ).toBe("expanded");
      expect(
        nextBoardSelection({ ...nothing, snap: "minimum" }, { type: "listVehicleSelected", vehicleKey: "bus-1" }).snap,
      ).toBe("minimum");
    });
  });

  it("목록 빈 곳을 누르면 버스 선택만 풀고 펼친 정류장은 둔다", () => {
    expect(nextBoardSelection(busSelected, { type: "listCleared" })).toEqual(stopExpandedInList);
  });

  describe("지도에서 버스를 누르면", () => {
    it("펼친 정류장은 그대로 두고 목록을 그 버스의 정류장으로 옮기며 마커에는 포커스를 주지 않는다", () => {
      expect(
        nextBoardSelection(stopExpandedInList, { type: "mapVehicleSelected", stop: stop8, vehicleKey: "bus-1" }),
      ).toEqual({
        ...stopExpandedInList,
        scrollRequest: stop8,
        vehicleFocusRequest: { key: "bus-1", focusMarker: false },
      });
    });

    it("같은 버스를 다시 눌러도 목록 이동을 새로 요청한다", () => {
      const first = nextBoardSelection(nothing, { type: "mapVehicleSelected", stop: stop8, vehicleKey: "bus-1" });
      const second = nextBoardSelection(first, { type: "mapVehicleSelected", stop: stop8, vehicleKey: "bus-1" });

      expect(second.scrollRequest).toEqual(stop8);
      expect(second.scrollRequest).not.toBe(first.scrollRequest);
    });

    it("시트가 최소 높이면 60% 높이로 연다", () => {
      const event = { type: "mapVehicleSelected", stop: stop8, vehicleKey: "bus-1" } as const;

      expect(nextBoardSelection({ ...nothing, snap: "minimum" }, event).snap).toBe("expanded");
      expect(nextBoardSelection({ ...nothing, snap: "full" }, event).snap).toBe("full");
    });
  });

  describe("지도에서 정류장을 누르면", () => {
    it("그 정류장을 고르고 목록을 옮기며 버스 선택을 푼다", () => {
      expect(nextBoardSelection(busSelected, { type: "mapStopSelected", stop: stop8 })).toEqual({
        ...nothing,
        selected: stop8,
        scrollRequest: stop8,
      });
    });

    it("목록에서 펼친 정류장을 지도에서 다시 누르면 목록에서 골랐다는 기록을 유지한다", () => {
      expect(nextBoardSelection(stopExpandedInList, { type: "mapStopSelected", stop: stop11 })).toEqual({
        ...stopExpandedInList,
        scrollRequest: stop11,
      });
    });

    it("시트가 최소 높이면 60% 높이로 연다", () => {
      expect(nextBoardSelection({ ...nothing, snap: "minimum" }, { type: "mapStopSelected", stop: stop8 }).snap).toBe(
        "expanded",
      );
    });
  });

  it("지도 빈 곳을 누르면 정류장과 버스 선택을 모두 푼다", () => {
    expect(nextBoardSelection(busSelected, { type: "mapCleared" })).toEqual({ ...nothing, selectedFromList: true });
  });

  it("방향 탭을 누르면 선택과 목록 이동 요청만 지우고 시트 높이는 둔다", () => {
    expect(nextBoardSelection({ ...busSelected, snap: "full" }, { type: "directionSwitched" })).toEqual({
      ...nothing,
      selectedFromList: true,
      snap: "full",
    });
  });
});
