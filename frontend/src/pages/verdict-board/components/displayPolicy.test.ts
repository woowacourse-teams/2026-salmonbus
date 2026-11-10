import { describe, expect, it } from "@jest/globals";
import type { HeadwayRange, RouteHeadways, Vehicle } from "@/shared/api/routeForecast.types";
import {
  boardFreshnessOf,
  boardNoticeFor,
  displayedStopViewsFor,
  headwayLabelFor,
  observedClockOf,
  serviceStateFor,
  stopViewsFor,
} from "./displayPolicy";
import { boardWith, stopAt, upInfo, vehicleAt } from "@/testing/fixtures";
import { forecastFixtures } from "@/testing/forecastFixtures";

const observedAt = (clock: string, offset = "+09:00") => `2026-09-01T${clock}:00${offset}`;
const upVehicle = vehicleAt(2);
const downVehicle = vehicleAt(5);
const noVehicles: Vehicle[] = [];

describe("headwayLabelFor", () => {
  const headways: RouteHeadways = {
    weekday: { minMinutes: 10, maxMinutes: 20 },
    saturday: { minMinutes: 15, maxMinutes: 25 },
    sunday: { minMinutes: 20, maxMinutes: 30 },
    holiday: { minMinutes: 30, maxMinutes: 40 },
  };

  it.each([
    ["2026-10-26", "10~20분"],
    ["2026-10-27", "10~20분"],
    ["2026-10-28", "10~20분"],
    ["2026-10-29", "10~20분"],
    ["2026-10-30", "10~20분"],
    ["2026-10-31", "15~25분"],
    ["2026-11-01", "20~30분"],
  ])("한국 시간 %s의 요일에 맞는 최소~최대 간격을 표시한다", (date, label) => {
    expect(headwayLabelFor(headways, Date.parse(`${date}T08:00:00+09:00`))).toBe(label);
  });

  it.each([
    ["2026-10-30T14:59:59Z", "10~20분"],
    ["2026-10-30T15:00:00Z", "15~25분"],
    ["2026-10-31T15:00:00Z", "20~30분"],
    ["2026-11-01T15:00:00Z", "10~20분"],
  ])("기기 시간대와 무관하게 %s의 한국 날짜를 사용한다", (date, label) => {
    expect(headwayLabelFor(headways, Date.parse(date))).toBe(label);
  });

  const weekdayNow = Date.parse("2026-10-27T08:00:00+09:00");

  it("headways가 없는 기존 응답은 정보 준비 중으로 표시한다", () => {
    expect(headwayLabelFor(undefined, weekdayNow)).toBe("정보 준비 중");
  });

  it.each(["2026-10-27T08:00:00+09:00", "2026-10-31T08:00:00+09:00", "2026-11-01T08:00:00+09:00"])(
    "오늘(%s)의 값이 없으면 다른 요일이나 공휴일 값으로 대신하지 않는다",
    (date) => {
      expect(
        headwayLabelFor({ weekday: null, saturday: null, sunday: null, holiday: headways.holiday }, Date.parse(date)),
      ).toBe("정보 준비 중");
    },
  );

  it("최소와 최대가 같아도 범위를 그대로 표시한다", () => {
    expect(headwayLabelFor({ ...headways, weekday: { minMinutes: 15, maxMinutes: 15 } }, weekdayNow)).toBe("15~15분");
  });

  it.each([
    undefined,
    "10~20",
    {},
    { minMinutes: "10", maxMinutes: 20 },
    { minMinutes: 10 },
    { minMinutes: 0, maxMinutes: 20 },
    { minMinutes: -1, maxMinutes: 20 },
    { minMinutes: 10.5, maxMinutes: 20 },
    { minMinutes: 10, maxMinutes: 20.5 },
    { minMinutes: 20, maxMinutes: 10 },
    { minMinutes: 10, maxMinutes: 1441 },
    { minMinutes: NaN, maxMinutes: Infinity },
  ])("잘못된 범위 %p는 정보 준비 중으로 표시한다", (value) => {
    const malformed = { ...headways, weekday: value as HeadwayRange };
    expect(headwayLabelFor(malformed, weekdayNow)).toBe("정보 준비 중");
  });

  it("계약상 최대 간격인 1440분도 표시한다", () => {
    expect(headwayLabelFor({ ...headways, weekday: { minMinutes: 1, maxMinutes: 1440 } }, weekdayNow)).toBe("1~1440분");
  });
});

describe("serviceStateFor", () => {
  describe("관측 시각이 시간표 안이면", () => {
    it("관측 시각이 첫차 시각과 같으면 이미 운행중이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("04:50") }), upInfo, noVehicles)).toBe("running");
    });

    it("관측 시각이 막차 시각과 같으면 아직 운행중이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("23:30") }), upInfo, noVehicles)).toBe("running");
    });

    it("자정을 넘기는 시간표는 새벽을 운행 중으로 본다", () => {
      const overnight = { ...upInfo, firstDepartureTime: "23:00", lastDepartureTime: "02:00" };

      expect(serviceStateFor(boardWith({ observedAt: observedAt("01:00") }), overnight, noVehicles)).toBe("running");
    });

    it("도는 차량이 없으면 오는 차량 없음이다", () => {
      expect(serviceStateFor(boardWith({ vehiclesInService: 0 }), upInfo, noVehicles)).toBe("noVehiclesInService");
    });
  });

  describe("관측 시각이 시간표 밖이면", () => {
    const afterLastDeparture = boardWith({ observedAt: observedAt("23:31") });

    it("막차가 떠난 뒤에도 이 방향 차량이 관측되면 운행 중이다", () => {
      expect(serviceStateFor(afterLastDeparture, upInfo, [upVehicle])).toBe("running");
    });

    it("자정이 지나도 이 방향 차량이 관측되면 운행 중이다", () => {
      const afterMidnight = boardWith({ observedAt: observedAt("00:10") });

      expect(serviceStateFor(afterMidnight, upInfo, [upVehicle])).toBe("running");
    });

    it("첫차 시각 전이라도 이 방향 차량이 관측되면 운행 중이다", () => {
      const beforeFirstDeparture = boardWith({ observedAt: observedAt("04:49") });

      expect(serviceStateFor(beforeFirstDeparture, upInfo, [upVehicle])).toBe("running");
    });

    it("노선에 도는 차량이 없으면 운행 종료이다", () => {
      const emptyRoute = boardWith({ observedAt: observedAt("23:31"), vehiclesInService: 0 });

      expect(serviceStateFor(emptyRoute, upInfo, noVehicles)).toBe("outOfService");
    });

    it("반대 방향 차량만 관측되면 운행 종료이다", () => {
      expect(serviceStateFor(afterLastDeparture, upInfo, [downVehicle])).toBe("outOfService");
    });

    it("도는 차량이 있어도 위치를 못 읽어 이 방향 차량이 하나도 없으면 운행 종료이다", () => {
      const unlocatedOnly = boardWith({ observedAt: observedAt("23:31"), vehiclesInService: 2 });

      expect(serviceStateFor(unlocatedOnly, upInfo, noVehicles)).toBe("outOfService");
    });

    it("자정을 넘기는 시간표에서 막차와 첫차 사이 시각도 같은 기준으로 판단한다", () => {
      const overnight = { ...upInfo, firstDepartureTime: "23:00", lastDepartureTime: "02:00" };
      const midday = boardWith({ observedAt: observedAt("12:00") });

      expect(serviceStateFor(midday, overnight, noVehicles)).toBe("outOfService");
    });
  });

  describe("관측 시각이 KST가 아니면", () => {
    it("시간표 판정을 포기하고 차량 수로만 정한다", () => {
      const utcObserved = observedAt("23:31", "+00:00");

      expect(serviceStateFor(boardWith({ observedAt: utcObserved, vehiclesInService: 2 }), upInfo, noVehicles)).toBe(
        "running",
      );
    });

    it("판정 불가인데 도는 차량이 없으면 오는 차량 없음이다", () => {
      expect(
        serviceStateFor(
          boardWith({ observedAt: observedAt("08:00", "+00:00"), vehiclesInService: 0 }),
          upInfo,
          noVehicles,
        ),
      ).toBe("noVehiclesInService");
    });
  });
});

describe("boardFreshnessOf", () => {
  it("만료면 status와 무관하게 expired다", () => {
    expect(boardFreshnessOf("success", true)).toBe("expired");
    expect(boardFreshnessOf("refetchError", true)).toBe("expired");
  });

  it("만료 전 재조회 실패는 refreshFailed이고 성공은 fresh다", () => {
    expect(boardFreshnessOf("refetchError", false)).toBe("refreshFailed");
    expect(boardFreshnessOf("success", false)).toBe("fresh");
  });
});

describe("observedClockOf", () => {
  it("KST 관측 시각만 HH:MM으로 읽고 그 외는 null이다", () => {
    expect(observedClockOf("2026-09-01T08:00:00+09:00")).toBe("08:00");
    expect(observedClockOf("2026-09-01T08:00:00+00:00")).toBeNull();
  });
});

describe("boardNoticeFor", () => {
  const observedAt = "2026-09-01T08:00:00+09:00";

  it("오는 차량 없음만 안내를 주고 나머지는 안내가 없다", () => {
    expect(boardNoticeFor("noVehiclesInService", "fresh", observedAt)).toEqual({ kind: "noVehiclesInService" });
    expect(boardNoticeFor("running", "fresh", observedAt)).toBeNull();
    expect(boardNoticeFor("outOfService", "fresh", observedAt)).toBeNull();
  });

  it("만료와 재조회 실패가 오는 차량 없음보다 우선하고 기준 시각을 붙인다", () => {
    expect(boardNoticeFor("noVehiclesInService", "expired", observedAt)).toEqual({
      kind: "expired",
      observedClock: "08:00",
    });
    expect(boardNoticeFor("noVehiclesInService", "refreshFailed", observedAt)).toEqual({
      kind: "refreshFailed",
      observedClock: "08:00",
    });
  });

  it("KST가 아니면 기준 시각을 null로 둔다", () => {
    expect(boardNoticeFor("running", "expired", "2026-09-01T08:00:00+00:00")).toEqual({
      kind: "expired",
      observedClock: null,
    });
  });
});

describe("stopViewsFor", () => {
  it("예상 좌석 수를 몰라도 확률이 있으면 그 등급으로 표시한다", () => {
    const board = boardWith({
      stops: [stopAt(2, { approachingVehicles: [forecastFixtures.withoutExpectedSeats] })],
    });

    expect(stopViewsFor(board, "UP")).toMatchObject([
      {
        kind: "boarding",
        tone: "high",
        arrivals: [{ kind: "available", level: "high", seatEstimate: { kind: "unknown" } }],
      },
    ]);
  });

  it("가장 가까운 차량의 예측이 없어도 접근 차량과 대표 판정에 남긴다", () => {
    const board = boardWith({
      stops: [
        stopAt(2, {
          approachingVehicles: [forecastFixtures.unavailable, { ...forecastFixtures.available, remainingStops: 7 }],
        }),
        stopAt(3),
      ],
    });

    expect(stopViewsFor(board, "UP")).toMatchObject([
      {
        sequence: 2,
        kind: "boarding",
        tone: "unavailable",
        arrivals: [
          { kind: "unavailable", stopsAway: 1 },
          { kind: "available", stopsAway: 7, level: "high", seatEstimate: { kind: "count", seats: 5 } },
        ],
      },
      { sequence: 3, kind: "noVehicle" },
    ]);
  });
});

describe("displayedStopViewsFor", () => {
  it("만료가 아니면 stopViewsFor와 같다", () => {
    const board = boardWith({
      stops: [
        stopAt(1, { boardingAllowed: false }),
        stopAt(2, { approachingVehicles: [forecastFixtures.available] }),
        stopAt(3),
      ],
    });

    expect(displayedStopViewsFor(board, "UP", "fresh")).toEqual(stopViewsFor(board, "UP"));
    expect(displayedStopViewsFor(board, "UP", "refreshFailed")).toEqual(stopViewsFor(board, "UP"));
  });

  it("만료면 탑승·무차량 정류장만 checking으로 바꾸고 경유는 그대로 둔다", () => {
    const board = boardWith({
      stops: [
        stopAt(1, { boardingAllowed: false }),
        stopAt(2, { approachingVehicles: [forecastFixtures.available] }),
        stopAt(3),
      ],
    });

    expect(displayedStopViewsFor(board, "UP", "expired")).toMatchObject([
      { sequence: 1, kind: "passThrough" },
      { sequence: 2, kind: "checking", stopId: "S2", name: expect.any(String) },
      { sequence: 3, kind: "checking", stopId: "S3" },
    ]);
  });
});
