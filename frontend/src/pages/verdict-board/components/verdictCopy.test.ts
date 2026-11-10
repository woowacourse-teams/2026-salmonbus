import { describe, expect, it } from "@jest/globals";
import { chipLabel } from "@/shared/components/verdictChipCopy";
import {
  arrivalSeatLabel,
  boardNoticeLabel,
  OUT_OF_SERVICE_NOTICE,
  REFRESH_FAILED_NOTICE,
  STALE_FORECAST_NOTICE,
} from "./verdictCopy";

describe("arrivalSeatLabel", () => {
  it("좌석 예보가 없는 차량은 예상 좌석을 모르는 문구로 표시한다", () => {
    expect(arrivalSeatLabel({ kind: "unavailable", vehicleId: "bus-1", stopsAway: 3 })).toBe("예상 좌석 정보 없음");
  });
});

describe("chipLabel", () => {
  it("예측 불가는 탑승 확률이 낮거나 접근 차량이 없는 상태와 구분한다", () => {
    expect(chipLabel("unavailable")).toBe("좌석 예측 정보가 없어요");
    expect(chipLabel("veryLow")).toBe("탑승 확률 매우 낮음");
    expect(chipLabel("noVehicle")).toBe("지금 오는 차량이 없어요");
  });

  it("만료 칩은 잠금 문구를 그대로 쓴다", () => {
    expect(chipLabel("checking")).toBe("정보가 오래됐어요");
  });
});

describe("boardNoticeLabel", () => {
  it("시트 안내와 칩 문구가 같고 운행 종료 문구와는 다르다", () => {
    expect(boardNoticeLabel({ kind: "noVehiclesInService" })).toBe("지금 오는 차량이 없어요");
    expect(boardNoticeLabel({ kind: "noVehiclesInService" })).toBe(chipLabel("noVehicle"));
    expect(OUT_OF_SERVICE_NOTICE).toBe("현재는 운행시간이 아닙니다");
  });

  it("만료와 재조회 실패는 같은 문장에 기준 시각을 붙인다", () => {
    expect(boardNoticeLabel({ kind: "expired", observedClock: "08:00" })).toBe(
      "새 정보를 불러오지 못했어요 · 08:00 기준",
    );
    expect(boardNoticeLabel({ kind: "refreshFailed", observedClock: "08:00" })).toBe(
      "새 정보를 불러오지 못했어요 · 08:00 기준",
    );
    expect(REFRESH_FAILED_NOTICE).toBe("새 정보를 불러오지 못했어요");
  });

  it("기준 시각이 없으면 시각 꼬리를 붙이지 않는다", () => {
    expect(boardNoticeLabel({ kind: "expired", observedClock: null })).toBe(REFRESH_FAILED_NOTICE);
  });

  it("펼친 패널 만료 안내는 칩과 다른 잠금 문구다", () => {
    expect(STALE_FORECAST_NOTICE).toBe("새 정보가 오면 좌석 예측을 다시 보여드려요");
    expect(STALE_FORECAST_NOTICE).not.toBe(chipLabel("checking"));
  });
});
