import { apiPaths, pollIntervalMs } from "./fixtures/mock-data";
import { centerY, expectForwardMotion } from "./utils/vehicle-motion";
import { test, expect } from "./fixtures/api";
import { directionButton, expectDirection, openBoard, targetRow, targetStop } from "./utils/verdict-board";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "no-preference",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

for (const { direction, label, sequence, expectedArrivals } of [
  {
    direction: "UP",
    label: "상행",
    sequence: 3,
    expectedArrivals: [/3정류장 전\s*도착 시 5석 예상해요/, /7정류장 전\s*도착 시 2석 예상해요/],
  },
  { direction: "DOWN", label: "하행", sequence: 15, expectedArrivals: [/2정류장 전\s*도착 시 8석 예상해요/] },
] as const) {
  test(`${label} 노선을 선택해 실시간 차량 이동과 정류장별 좌석 예측을 확인한다`, async ({ page, api }) => {
    const stop = api.data.board.stops.find((stop) => stop.sequence === sequence)!;
    const nextStop = api.data.board.stops.find((stop) => stop.sequence === sequence + 1)!;
    const vehicle = {
      vehicleId: "moving-bus",
      direction,
      currentStopSequence: sequence,
      stopId: stop.stopId,
      stopName: stop.name,
      phase: "DEPARTED" as const,
      seat: { kind: "EXACT" as const, remaining: 5 },
    };
    api.data.vehicles.vehicles = [vehicle];

    await test.step("노선 선택 페이지에서 3330번 노선을 선택한다", async () => {
      await openBoard(page);
    });

    await test.step(`${label} 방향의 정류장 목록과 순서를 확인한다`, async () => {
      await directionButton(page, direction).click();
      await expectDirection(page, direction);
    });

    await test.step("실시간 차량이 출발·이동·다음 정류장 도착 위치로 이어 움직인다", async () => {
      const tracker = page.getByRole("img", { name: /현재 잔여 좌석 5석/ });
      const marker = tracker.locator("..");
      await expect
        .poll(async () => {
          await page.clock.runFor(20);
          return tracker.count();
        })
        .toBe(1);
      await expect(tracker).toBeInViewport();
      await expect(tracker).toHaveAttribute("aria-label", `${stop.name} 정류장, 출발함, 현재 잔여 좌석 5석`);

      const from = await centerY(page.locator(`[data-timeline-sequence="${sequence}"]`));
      const to = await centerY(page.locator(`[data-timeline-sequence="${sequence + 1}"]`));
      const departed = await expectForwardMotion(page, marker, from, to);

      for (const step of [
        { phase: "IN_TRANSIT", stop, description: "다음 정류장으로 이동 중" },
        { phase: "ARRIVING", stop: nextStop, description: "도착 중" },
      ] as const) {
        const previousY = await centerY(marker);
        api.data.vehicles.vehicles = [
          {
            ...vehicle,
            phase: step.phase,
            currentStopSequence: step.stop.sequence,
            stopId: step.stop.stopId,
            stopName: step.stop.name,
          },
        ];
        const requestsBefore = api.requests.vehicles;
        const response = page.waitForResponse(
          (response) => new URL(response.url()).pathname === apiPaths.vehicles && response.status() === 200,
        );
        await page.clock.fastForward(pollIntervalMs);
        await (await response).finished();
        await expect(tracker).toHaveAttribute(
          "aria-label",
          `${step.stop.name} 정류장, ${step.description}, 현재 잔여 좌석 5석`,
        );
        expect(api.requests.vehicles).toBeGreaterThan(requestsBefore);

        const motion = await expectForwardMotion(page, marker, from, to);
        // 같은 차량은 응답 갱신 때 순간이동하지 않고 이전 표시 위치에서 이어 움직인다.
        expect(Math.abs(motion.start - previousY)).toBeLessThan(1);
        expect(motion.end).toBeGreaterThan(departed.end + 1);
      }
    });

    await test.step("목표 정류장까지 스크롤하고 펼쳐 예측을 확인한다", async () => {
      const stop = targetStop(page, direction);
      const row = targetRow(page, direction);
      await expect(stop).not.toBeInViewport();
      await stop.scrollIntoViewIfNeeded();
      await expect(stop).toBeInViewport();
      await expect(stop).toHaveAttribute("aria-expanded", "false");
      await expect(row.getByText("탑승 확률 높음", { exact: true })).toBeVisible();

      await stop.click();
      await expect(stop).toHaveAttribute("aria-expanded", "true");
      const arrivals = row.getByRole("listitem");
      await expect(arrivals).toHaveText([...expectedArrivals]);
      for (let index = 0; index < expectedArrivals.length; index += 1) {
        await expect(arrivals.nth(index)).toBeVisible();
      }
    });
  });
}
