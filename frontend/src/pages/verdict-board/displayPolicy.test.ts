import { describe, expect, it } from "@jest/globals";
import { serviceStateFor } from "./displayPolicy";
import { boardWith, upInfo } from "../../testing/fixtures";

const observedAt = (clock: string, offset = "+09:00") => `2026-09-01T${clock}:00${offset}`;

describe("serviceStateFor", () => {
  describe("시간표를 읽을 수 있는 상황이면", () => {
    it("관측 시각이 막차 시각과 같으면 아직 운행중이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("23:30") }), upInfo)).toBe("running");
    });

    it("관측 시각이 막차 시각을 지났다면 운행 종료이다", () => {
      expect(serviceStateFor(boardWith({ observedAt: observedAt("23:31") }), upInfo)).toBe("outOfService");
    });

    it("자정을 넘기는 시간표는 새벽을 운행 중으로 본다", () => {
      const overnight = { ...upInfo, firstDepartureTime: "23:00", lastDepartureTime: "02:00" };

      expect(serviceStateFor(boardWith({ observedAt: observedAt("01:00") }), overnight)).toBe("running");
      expect(serviceStateFor(boardWith({ observedAt: observedAt("12:00") }), overnight)).toBe("outOfService");
    });

    it("운행 시간이라도 도는 차량이 없으면 운행 종료이다", () => {
      expect(serviceStateFor(boardWith({ vehiclesInService: 0 }), upInfo)).toBe("outOfService");
    });
  });

  describe("관측 시각이 KST가 아니면", () => {
    it("시간표 판정을 포기하고 차량 수로만 정한다", () => {
      const utcObserved = observedAt("23:31", "+00:00");

      expect(serviceStateFor(boardWith({ observedAt: utcObserved, vehiclesInService: 2 }), upInfo)).toBe("running");
      expect(serviceStateFor(boardWith({ observedAt: utcObserved, vehiclesInService: 0 }), upInfo)).toBe(
        "outOfService",
      );
    });
  });
});
