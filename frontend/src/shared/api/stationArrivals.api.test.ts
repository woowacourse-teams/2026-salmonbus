import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { initApiFailureReporter, resetApiFailureReporter, type ApiFailureReporter } from "./client";
import { fetchStationArrivals } from "./stationArrivals.api";
import type { StationArrivalSelection } from "./stationArrivals.types";

const originalFetch = global.fetch;
const selection: StationArrivalSelection = {
  stationId: "206000637",
  routeId: "204000057",
  referenceVersionId: "9223372036854775807",
  stopSequence: 22,
  direction: "UP",
};

afterEach(() => {
  global.fetch = originalFetch;
  resetApiFailureReporter();
});

describe("fetchStationArrivals", () => {
  it("선택 노선과 판본·방문 순번을 함께 전달하고 64비트 판본 문자열을 보존한다", async () => {
    const controller = new AbortController();
    global.fetch = jest.fn(async () => new Response("{}", { status: 200 })) as typeof fetch;

    await fetchStationArrivals(selection, { signal: controller.signal });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v2/stations/206000637/arrivals?routeId=204000057&referenceVersionId=9223372036854775807&stopSequence=22",
      expect.objectContaining({ headers: { Accept: "application/json" }, signal: expect.any(AbortSignal) }),
    );
  });

  it("경로와 쿼리를 인코딩하고 오류 문맥에는 원래 식별자를 전달한다", async () => {
    const reportFailure = jest.fn<ApiFailureReporter>();
    initApiFailureReporter(reportFailure);
    global.fetch = jest.fn(
      async () =>
        new Response(JSON.stringify({ code: "SERVICE_UNAVAILABLE", message: "장애", requestId: "arrival-1" }), {
          status: 503,
        }),
    ) as typeof fetch;

    await fetchStationArrivals(
      { ...selection, stationId: "S/ ?#한글%", routeId: "R&= /", referenceVersionId: "7&visit=2" },
      { signal: new AbortController().signal },
    );

    const url =
      "/api/v2/stations/S%2F%20%3F%23%ED%95%9C%EA%B8%80%25/arrivals?routeId=R%26%3D+%2F&referenceVersionId=7%26visit%3D2&stopSequence=22";
    expect(global.fetch).toHaveBeenCalledWith(url, expect.anything());
    expect(reportFailure).toHaveBeenCalledWith(url, expect.anything(), {
      context: {
        endpoint: "/api/v2/stations/:stationId/arrivals",
        tags: { route_id: "R&= /", station_id: "S/ ?#한글%" },
      },
      abortReason: null,
      rawResponse: null,
    });
  });

  it("취소된 선택은 네트워크 요청을 시작하지 않는다", async () => {
    const controller = new AbortController();
    controller.abort();
    global.fetch = jest.fn<typeof fetch>();

    const result = await fetchStationArrivals(selection, { signal: controller.signal });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result).toEqual({ ok: false, failure: { kind: "aborted" } });
  });
});
