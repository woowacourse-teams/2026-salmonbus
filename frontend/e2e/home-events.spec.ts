import { mergeTests } from "@playwright/test";
import { test, expect } from "./fixtures/naver-map";
import { test as authTest, testMember } from "./fixtures/auth";
import { apiPaths, observedAt, routeId } from "./fixtures/mock-data";
import { createStationArrivalsData } from "./fixtures/station-arrivals";
import { openRouteSearch, pickRoute } from "./utils/route-select";

const pangyoPoint: [number, number] = [127.111136, 37.394761];
const signedInTest = mergeTests(test, authTest);

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

signedInTest("홈에서 날씨를 갱신하고 로그인 인사를 탭바 위에 표시한다", async ({ page, auth, naverMap }) => {
  await page.setViewportSize({ width: 390, height: 720 });
  auth.member = { ...testMember };
  let releaseMovedWeather!: () => void;
  const movedWeather = new Promise<void>((resolve) => {
    releaseMovedWeather = resolve;
  });
  const requestedLongitudes: (string | null)[] = [];
  await page.route("https://api.open-meteo.com/**", async (route) => {
    const longitude = new URL(route.request().url()).searchParams.get("longitude");
    requestedLongitudes.push(longitude);
    const atPangyo = longitude === "127.111";
    if (!atPangyo) await movedWeather;
    return route.fulfill({
      json: {
        current: {
          temperature_2m: atPangyo ? 18 : 13,
          weather_code: 1,
          is_day: atPangyo ? 1 : 0,
          time: observedAt.slice(0, 16),
        },
        hourly: {
          time: atPangyo
            ? [9, 10, 11, 12, 13, 14].map((hour) => `2026-09-16T${String(hour).padStart(2, "0")}:00`)
            : ["2026-09-16T22:00", "2026-09-16T23:00", "2026-09-17T00:00", "2026-09-17T01:00", "2026-09-17T02:00"],
          temperature_2m: atPangyo ? [19, 20, 21, 22, 23, 24] : [12, 11, 10, 9, 8],
          weather_code: [0, 2, 61, 0, 1, 0],
          is_day: atPangyo ? [1, 1, 1, 1, 1, 1] : [0, 0, 0, 0, 0],
          precipitation_probability: [10, 30, 50, 0, 0, 0],
        },
      },
    });
  });
  await page.goto("/");
  const home = page.getByRole("region", { name: "홈 정보", exact: true });
  const weather = home.getByRole("region", { name: "현재 지역 날씨" });
  await expect(weather.getByText("18°", { exact: true })).toBeVisible();
  const icon = weather.getByRole("img", { name: "대체로 맑음", exact: true }).first();
  await expect(icon).toBeVisible();
  await expect(weather).toHaveAttribute("data-weather", "sun");
  const forecast = weather.getByRole("list", { name: "시간대별 날씨 예보" });
  const hours = forecast.getByRole("listitem");
  await expect(hours).toHaveCount(6);
  await expect(hours.first()).toHaveAttribute("aria-current", "time");
  await expect(hours.first().getByText("지금", { exact: true })).toBeVisible();
  await expect(hours.nth(1).getByText("오전 9시", { exact: true })).toBeVisible();
  await expect(hours.first().getByText("10%", { exact: true })).toBeVisible();
  await expect(hours.nth(3).getByRole("img", { name: "비", exact: true })).toBeVisible();
  await expect(hours.nth(3).getByText("50%", { exact: true })).toBeVisible();
  const greeting = home.getByText(`${testMember.nickname}님,`, { exact: true }).locator("..");
  await expect(greeting).toContainText("오늘도 힘찬 하루 보내세요!");
  const viewportHeight = await page.evaluate(() => window.visualViewport?.height ?? window.innerHeight);
  const sheetBounds = (await home.boundingBox())!;
  const greetingBounds = (await greeting.boundingBox())!;
  const navigationBounds = (await page.getByRole("navigation", { name: "주요 메뉴" }).boundingBox())!;
  expect(Math.abs(sheetBounds.height - viewportHeight * 0.38)).toBeLessThan(1);
  expect(greetingBounds.y + greetingBounds.height).toBeLessThan(navigationBounds.y);
  const sheetSurface = home.locator("..");
  const previousBackground = await sheetSurface.evaluate((element) => getComputedStyle(element).backgroundImage);
  expect(previousBackground).toContain("linear-gradient");

  await page.clock.setSystemTime(new Date("2026-09-16T21:30:00+09:00"));
  try {
    await naverMap.panToPoint([127.154, 37.43]);
    await expect(weather.getByText("18°", { exact: true })).toBeVisible();
    expect(requestedLongitudes).toEqual(["127.111"]);
    await weather.getByRole("button", { name: "지도 중심 날씨 새로고침" }).click();
    await expect(weather.locator('[aria-busy="true"]')).toBeVisible();
    await expect(sheetSurface).toHaveCSS("background-image", previousBackground);
  } finally {
    releaseMovedWeather();
  }
  await expect(weather.getByText("13°", { exact: true })).toBeVisible();
  expect(requestedLongitudes).toEqual(["127.111", "127.154"]);
  await expect(icon).toBeVisible();
  await expect(weather).toHaveAttribute("data-weather", "night");
  await expect(sheetSurface).not.toHaveCSS("background-image", previousBackground);
  await page.clock.runFor(60_000);
  await expect(hours.nth(3).locator("time")).toHaveAttribute("datetime", "2026-09-17T00:00+09:00");
  await expect(hours.nth(3).getByText("오전 12시", { exact: true })).toBeVisible();
  await expect(forecast.getByText("8°", { exact: true })).toBeVisible();
  await expect(greeting).toContainText("오늘 하루도 고생 많으셨어요.");
});

test("정류장 타일 실패를 재시도하고 생성 대기 타일은 화면 복귀 후 불러온다", async ({ page }) => {
  let attempts = 0;
  await page.route("**/api/v2/stations/tiles/14/*/*", (route) => {
    const [, , zoom, x, y] = new URL(route.request().url()).pathname.split("/").slice(-5).map(Number);
    const isPangyo = x === 13976 && y === 6354;
    if (isPangyo && ++attempts === 1) return route.fulfill({ status: 503 });
    return route.fulfill({
      json: {
        tile: { zoom, x, y },
        coordinateSystem: "WGS84",
        status: isPangyo && attempts === 2 ? "PENDING" : "READY",
        stations:
          isPangyo && attempts > 2
            ? [{ stationId: "206000532", name: "판교역 API", point: { x: pangyoPoint[0], y: pangyoPoint[1] } }]
            : [],
      },
    });
  });
  await page.goto("/");
  await expect(page.getByText("주변 정류장을 모두 불러오지 못했어요.")).toBeVisible();
  const pendingTile = page.waitForResponse(
    (response) => response.url().endsWith("/tiles/14/13976/6354") && response.ok(),
  );
  await page.getByRole("button", { name: "정류장 다시 불러오기" }).click();
  await pendingTile;
  for (const state of ["hidden", "visible"]) {
    await page.evaluate((visibilityState) => {
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibilityState });
      document.dispatchEvent(new Event("visibilitychange"));
    }, state);
  }
  await expect(page.getByRole("button", { name: "판교역 API 정류장" })).toBeVisible();
  await expect(page.getByText("주변 정류장을 모두 불러오지 못했어요.")).toHaveCount(0);
  expect(attempts).toBe(3);
});

test("정류장 상세 조회 실패는 재시도할 수 있고 정상 빈 응답은 따로 표시한다", async ({ page }) => {
  const stationId = "206000532";
  await page.route("**/api/v2/stations/tiles/14/*/*", (route) => {
    const [, , zoom, x, y] = new URL(route.request().url()).pathname.split("/").slice(-5).map(Number);
    return route.fulfill({
      json: {
        tile: { zoom, x, y },
        coordinateSystem: "WGS84",
        status: "READY",
        stations:
          x === 13976 && y === 6354
            ? [{ stationId, name: "판교역 API", point: { x: pangyoPoint[0], y: pangyoPoint[1] } }]
            : [],
      },
    });
  });
  await page.route(`**/api/v2/stations/${stationId}`, (route) =>
    route.fulfill({
      json: {
        stationId,
        generatedAt: observedAt,
        status: "READY",
        refresh: { status: "IDLE", suggestedRefreshAt: observedAt },
        routes: [],
      },
    }),
  );
  await page.route(`**/api/v2/stations/${stationId}`, (route) => route.fulfill({ status: 503 }), { times: 1 });
  await page.goto("/");
  await page.getByRole("button", { name: "판교역 API 정류장" }).press("Enter");
  const sheet = page.getByRole("region", { name: "정류장 노선 안내" });
  await expect(sheet.getByText("정류장 버스 정보를 불러오지 못했어요.")).toBeVisible();
  await sheet.getByRole("button", { name: "정류장 버스 정보 다시 불러오기" }).click();
  await expect(sheet.getByText("이 정류장의 정차 노선 정보가 없어요.")).toBeVisible();
  await expect(sheet.getByRole("button", { name: "정류장 버스 정보 다시 불러오기" })).toHaveCount(0);
});

for (const status of ["UNAVAILABLE", "READY"] as const) {
  test(`정류장 상세에서 GBIS가 ${status}여도 첫 판정보드 차량의 거리와 예보를 표시한다`, async ({ page }) => {
    const stationId = "206000532";
    await page.route("**/api/v2/stations/tiles/14/*/*", (route) => {
      const [, , zoom, x, y] = new URL(route.request().url()).pathname.split("/").slice(-5).map(Number);
      return route.fulfill({
        json: {
          tile: { zoom, x, y },
          coordinateSystem: "WGS84",
          status: "READY",
          stations:
            x === 13976 && y === 6354
              ? [{ stationId, name: "판교역 API", point: { x: pangyoPoint[0], y: pangyoPoint[1] } }]
              : [],
        },
      });
    });
    await page.route(`**/api/v2/stations/${stationId}`, (route) =>
      route.fulfill({
        headers: { date: new Date(observedAt).toUTCString() },
        json: {
          stationId,
          generatedAt: observedAt,
          status,
          reason: status === "UNAVAILABLE" ? "STALE" : null,
          snapshot: { staleAt: status === "READY" ? "2026-09-16T08:05:00+09:00" : observedAt },
          refresh: { status: "IDLE", suggestedRefreshAt: "2026-09-16T08:01:00+09:00" },
          routes: [
            {
              routeId,
              name: "3330",
              supported: true,
              service: { matchStatus: "MATCHED", direction: "UP" },
              arrivals: [
                {
                  positionSource: "BOARD",
                  remainingStops: 1,
                  eta: { status: "UNAVAILABLE" },
                  forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9 },
                },
                {
                  positionSource: "BOARD",
                  remainingStops: 4,
                  eta:
                    status === "READY"
                      ? { status: "AVAILABLE", expectedArrivalAt: "2026-09-16T08:03:00+09:00", remainingSeconds: 180 }
                      : { status: "UNAVAILABLE" },
                  forecast: { status: "AVAILABLE", seatAvailableProbability: 0.1 },
                },
              ],
            },
          ],
        },
      }),
    );
    await page.goto("/");
    await page.getByRole("button", { name: "판교역 API 정류장" }).press("Enter");
    const row = page.getByRole("region", { name: "정류장 노선 안내" }).getByRole("listitem").first();
    await expect(row).toContainText("도착 정보 없음");
    await expect(row).toContainText("1정류장 전");
    await expect(row).toContainText("탑승 확률 높음");
  });
}

test("지도 SDK가 인증 실패로 초기화 상태를 지워도 탭을 이동할 수 있다", async ({ page }) => {
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({ json: { stops: [], routePaths: [[pangyoPoint, [126.84, 37.52]]] } }),
  );
  await page.goto("/route-map");
  await expect(page.getByTestId("naver-map-test-double")).toBeVisible();
  const navigation = page.getByRole("navigation", { name: "주요 메뉴" });
  await expect(page.getByRole("list", { name: "서비스 중 노선" })).toBeVisible();
  await page.clock.runFor(64);
  await expect(page.getByTestId("home-route-network")).toBeAttached();

  await page.evaluate(() => {
    window.naver!.maps.Event.removeListener = () => {
      throw new Error("이미 제거된 지도 SDK");
    };
    delete window.naver;
    window.navermap_authFailure?.();
  });
  await expect(page.getByText("지도 인증을 확인하지 못했어요")).toBeVisible();
  await navigation.getByRole("link", { name: "홈", exact: true }).click();
  await expect(page.getByRole("region", { name: "홈 정보", exact: true })).toBeVisible();
  await expect(page.getByText("앗, 문제가 생겼어요")).toHaveCount(0);

  await navigation.getByRole("link", { name: "노선도", exact: true }).click();
  await page.getByRole("list", { name: "서비스 중 노선" }).getByRole("link", { name: /3330/ }).click();
  await expect(page.getByRole("region", { name: "판정보드 정류장 목록" })).toBeVisible();
  const boardMap = page.getByRole("region", { name: "노선 지도" });
  await expect(boardMap.getByText("지도를 불러오지 못했어요")).toBeVisible();
  await expect(boardMap).toHaveAttribute("aria-busy", "false");
});

test("노선 카드에서 기존 지도 위에 판정보드를 열고 노선 응답 뒤 경로를 그린다", async ({ page, api, naverMap }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  api.data.routes.routes[0]!.endStopName = "서울역버스환승센터(5번승강장)(중)";
  let releaseGeometry!: () => void;
  const geometryPending = new Promise<void>((resolve) => {
    releaseGeometry = resolve;
  });
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname === apiPaths.geometry) await geometryPending;
    await route.fallback();
  });
  await page.goto("/");
  const tab = page.getByRole("navigation", { name: "주요 메뉴" }).getByRole("link", { name: "노선도", exact: true });
  await tab.click();
  await expect(page).toHaveURL("/route-map");
  await expect(tab).toHaveAttribute("aria-current", "page");
  const routes = page.getByRole("list", { name: "서비스 중 노선" });
  const map = page.getByTestId("naver-map-test-double");
  await expect(map).toBeVisible();
  const canvas = await map.elementHandle();
  const card = routes.getByRole("link", { name: /3330/ });
  await expect(card).toBeVisible();
  const details = card.getByText("(5번승강장)(중)", { exact: true });
  await expect(details).toBeVisible();
  const nameBox = (await card.getByText("서울역버스환승센터", { exact: true }).boundingBox())!;
  const detailsBox = (await details.boundingBox())!;
  expect(detailsBox.x).toBeGreaterThan(nameBox.x);
  expect(detailsBox.y + detailsBox.height / 2).toBeCloseTo(nameBox.y + nameBox.height / 2, 0);
  expect(detailsBox.height).toBeLessThan(nameBox.height);
  const dotCenters = await card
    .locator("[title]")
    .evaluateAll((endpoints) =>
      endpoints.map(
        (endpoint) => endpoint.getBoundingClientRect().top + parseFloat(getComputedStyle(endpoint, "::before").top),
      ),
    );
  const badge = (await card.getByText("3330", { exact: true }).boundingBox())!;
  expect(dotCenters).toHaveLength(2);
  expect((dotCenters[0]! + dotCenters[1]!) / 2).toBeCloseTo(badge.y + badge.height / 2, 0);
  await expect(page.getByRole("textbox")).toBeVisible();
  await expect(page.getByRole("region", { name: "주변 행사 영향" })).toHaveCount(0);
  const camera = await naverMap.snapshot();
  try {
    await card.click();
    await expect(page).toHaveURL(`/routes/${routeId}`);
    await expect(page.getByRole("region", { name: "판정보드", exact: true })).toBeVisible();
    await expect(page.getByText("지도 정보를 준비 중이에요", { exact: true })).toHaveCount(0);
    await expect(map.getByTestId("naver-polyline")).toHaveCount(0);
    expect(await naverMap.snapshot()).toEqual(camera);
    expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  } finally {
    releaseGeometry();
  }
  await expect(map.getByTestId("naver-polyline")).toHaveCount(2);
  await naverMap.setZoom(14);
  await page.clock.runFor(64);
  await expect(map.getByRole("button", { name: /정류장 지도에서 선택/ }).first()).toBeVisible();
  expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  await page.goBack();
  await expect(page).toHaveURL("/route-map");
  await expect(card).toBeVisible();
});

test("노선도에서 연 노선의 최초 조회가 503이면 지도 위에 판정보드 바텀시트를 연다", async ({ page, api }) => {
  api.mode.watch = { status: 503, retryAfterSeconds: 30 };
  await page.goto("/route-map");
  const map = page.getByTestId("naver-map-test-double");
  await expect(map).toBeVisible();
  await page.getByRole("list", { name: "서비스 중 노선" }).getByRole("link", { name: /3330/ }).click();

  const sheet = page.getByRole("region", { name: "판정보드", exact: true });
  await expect(sheet.getByRole("slider", { name: "판정보드 높이" })).toHaveAttribute("aria-valuetext", "화면 60% 표시");
  await expect(sheet.getByRole("alert")).toHaveText("노선 정보를 불러오지 못했어요");
  await expect(sheet.getByRole("button", { name: "다시 시도하기", exact: true })).toBeInViewport({ ratio: 1 });
  await expect(map).toBeVisible();
});

test("노선도에서 운행이 끝난 방향을 열면 판정보드 바텀시트 중앙에 안내가 보인다", async ({ page, api }) => {
  api.data.watch.observedAt = "2026-09-16T23:30:00+09:00";
  api.data.watch.vehiclesInService = 0;
  api.data.watch.vehicles = [];
  await page.goto("/route-map");
  const card = page.getByRole("list", { name: "서비스 중 노선" }).getByRole("link", { name: /3330/ });
  await card.click();
  await expect(page).toHaveURL(`/routes/${routeId}`);
  const board = page.getByRole("region", { name: "판정보드", exact: true });
  await expect(board).toHaveAttribute("data-snap", "expanded");
  await board.evaluate((element) => Promise.allSettled(element.getAnimations().map((animation) => animation.finished)));
  const notice = board.getByRole("status");
  await expect(notice).toHaveText("현재는 운행시간이 아닙니다");
  await expect(notice).toBeInViewport({ ratio: 1 });
  const mascot = board.locator("img");
  await expect(mascot).toBeInViewport({ ratio: 1 });
  const imageBox = (await mascot.boundingBox())!;
  const noticeBox = (await notice.boundingBox())!;
  const sheetBox = (await board.boundingBox())!;
  expect(imageBox.y + imageBox.height).toBeLessThan(noticeBox.y);
  expect(imageBox.x + imageBox.width / 2).toBeCloseTo(noticeBox.x + noticeBox.width / 2, 0);
  const contentCenter = (imageBox.y + noticeBox.y + noticeBox.height) / 2;
  expect(Math.abs(contentCenter - (sheetBox.y + sheetBox.height / 2))).toBeLessThan(24);
  const map = page.getByTestId("naver-map-test-double");
  const paths = map.getByTestId("naver-polyline");
  await expect(paths).toHaveCount(2);
  await page.clock.runFor(64);
  await expect(paths.first().locator("polyline")).toHaveAttribute("stroke-opacity", "0.95");
  await expect(map.getByRole("button", { name: /정류장 지도에서 선택/ })).toHaveCount(0);
  const back = page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true });
  await expect(back).toBeInViewport({ ratio: 1 });
  await back.click();
  await expect(page).toHaveURL("/route-map");
  await expect(card).toBeVisible();
  await expect(paths).toHaveCount(0);
});

test("검색에서 같은 지도 위에 판정보드를 먼저 열고 응답이 오면 노선을 표시한다", async ({ page, api, naverMap }) => {
  let releaseGeometry!: () => void;
  const geometryPending = new Promise<void>((resolve) => {
    releaseGeometry = resolve;
  });
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname === apiPaths.geometry) await geometryPending;
    await route.fallback();
  });
  await page.goto("/");
  const map = page.getByTestId("naver-map-test-double");
  await expect(map).toBeVisible();
  const canvas = await map.elementHandle();
  const camera = await naverMap.snapshot();
  await openRouteSearch(page);
  expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  const board = page.getByRole("region", { name: "판정보드", exact: true });
  try {
    await pickRoute(page);
    await expect(board).toHaveAttribute("data-entering-from-map", "true");
    await expect(page.getByText("지도 정보를 준비 중이에요", { exact: true })).toHaveCount(0);
    await expect(map.getByTestId("naver-polyline")).toHaveCount(0);
    expect(await naverMap.snapshot()).toEqual(camera);
    expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  } finally {
    releaseGeometry();
  }
  await expect(map.getByTestId("naver-polyline")).toHaveCount(2);
  await page.clock.runFor(64);
  await page.getByRole("link", { name: "노선 선택으로 돌아가기" }).click();
  await expect(page).toHaveURL("/");
  expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  await expect(map.getByTestId("naver-polyline")).toHaveCount(0);
});

test("노선 탭에서 지원 노선 전체를 보여주고 다른 탭으로 나가면 기존 시점을 복원한다", async ({ page, naverMap }) => {
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({
      json: {
        generatedAt: observedAt,
        events: [],
        stops: [{ id: "10001", name: "공원 앞", point: pangyoPoint, routes: [] }],
        routePaths: [
          [pangyoPoint, [126.84, 37.52]],
          [pangyoPoint, [127.29, 37.22]],
        ],
      },
    }),
  );
  await page.goto("/");
  const map = page.getByTestId("naver-map-test-double");
  await expect(map).toBeVisible();
  const originalMap = await map.elementHandle();
  const searchBar = page.locator("main header").first();
  const initialSearch = await searchBar.boundingBox();
  const network = page.getByTestId("home-route-network");
  const nav = page.getByRole("navigation", { name: "주요 메뉴" });
  await expect(page.getByRole("button", { name: "공원 앞 정류장" })).toBeVisible();
  await expect(network).toHaveCount(0);
  await expect(page.getByRole("region", { name: "현재 지역 날씨" })).toBeVisible();
  await naverMap.panToPoint([127.154, 37.43]);
  await naverMap.setZoom(15);
  const camera = await naverMap.snapshot();
  await nav.getByRole("link", { name: "노선도", exact: true }).click();
  const panel = page.getByRole("region", { name: "선택한 탭 내용" });
  await expect(panel.getByRole("list", { name: "서비스 중 노선" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "서비스 중인 노선" })).toHaveCount(0);
  await page.clock.runFor(64);
  await expect(network).toBeVisible();
  const geometry = network.locator("defs > path");
  await expect(geometry).toHaveAttribute("d", /^M[^ML]+L[^ML]+M[^ML]+L[^ML]+$/);
  const routeStroke = network.locator(":scope > g > use").last();
  const bounds = await routeStroke.evaluate((stroke: SVGUseElement) => {
    const svg = stroke.ownerSVGElement!.getBoundingClientRect();
    const { x, y, width, height } = stroke.getBBox();
    return { left: svg.left + x, right: svg.left + x + width, top: svg.top + y, bottom: svg.top + y + height };
  });
  const search = (await searchBar.boundingBox())!;
  const sheetTop = (await page.getByRole("region", { name: "서비스 중인 노선", exact: true }).boundingBox())!.y;
  const mapBox = (await map.boundingBox())!;
  expect(bounds.left).toBeGreaterThan(mapBox.x);
  expect(bounds.right).toBeLessThan(mapBox.x + mapBox.width);
  expect(bounds.top).toBeGreaterThan(search.y + search.height);
  expect(bounds.bottom).toBeLessThan(sheetTop);
  expect(await originalMap?.evaluate((node) => node.isConnected)).toBe(true);
  expect(await searchBar.boundingBox()).toEqual(initialSearch);

  for (const [tab, heading] of [
    ["즐겨찾기", "즐겨찾기"],
    ["마이페이지", "마이페이지"],
  ] as const) {
    await nav.getByRole("link", { name: tab, exact: true }).click();
    await expect(
      page.getByRole("region", { name: "선택한 탭 내용" }).getByRole("heading", { name: heading, exact: true }),
    ).toBeVisible();
    expect(await originalMap?.evaluate((node) => node.isConnected)).toBe(true);
    expect(await searchBar.boundingBox()).toEqual(initialSearch);
    await expect.poll(() => naverMap.snapshot()).toEqual(camera);
    await expect(network).toHaveCount(0);
  }
  await nav.getByRole("link", { name: "홈", exact: true }).click();
  expect(await naverMap.snapshot()).toEqual(camera);
  await page.getByRole("link", { name: "연어버스 홈으로 이동" }).click();
  await expect
    .poll(() => naverMap.snapshot())
    .toMatchObject({ longitude: pangyoPoint[0], latitude: pangyoPoint[1], zoom: 14 });
});

test("노선도 등장 애니메이션이 끝나기 전에 노선 끝까지 드러난다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.clock.resume();
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({ json: { stops: [], routePaths: [[pangyoPoint, [127.29, 37.22]]] } }),
  );
  await page.goto("/");
  await page.addStyleTag({ content: '[data-testid="home-route-network"] > g { animation-play-state: paused; }' });
  await page.getByRole("link", { name: "노선도", exact: true }).click();
  const network = page.getByTestId("home-route-network");
  await expect(network.locator("defs > path")).toHaveAttribute("d", /^M[^ML]+L[^ML]+$/);

  const routeStroke = network.locator(":scope > g > use").last();
  const { radius, distanceToEnd } = await routeStroke.evaluate((stroke: SVGUseElement) => {
    const group = stroke.parentElement!;
    const [reveal] = group.getAnimations();
    if (!reveal?.effect) throw new Error("노선 등장 애니메이션을 찾지 못했습니다");
    reveal.currentTime = Number(reveal.effect.getTiming().duration) - 1;
    const clip = getComputedStyle(group).clipPath;
    const circle = clip.match(/^circle\(([\d.]+)px at ([\d.]+)px ([\d.]+)px\)/);
    if (!circle) throw new Error(`노선 등장 애니메이션의 원을 읽지 못했습니다: ${clip}`);
    const [, radius, centerX, centerY] = circle.map(Number);
    const { x, y, width, height } = stroke.getBBox();
    return { radius, distanceToEnd: Math.hypot(x + width - centerX!, y + height - centerY!) };
  });
  expect(radius).toBeGreaterThan(distanceToEnd);
});

test("홈과 즐겨찾기에서 정류장 핀으로 도착과 탑승 예보를 읽고 노선도에서는 핀을 숨긴다", async ({
  page,
  api,
  naverMap,
}) => {
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({ json: { stops: [], routePaths: [[pangyoPoint, [126.84, 37.52]]] } }),
  );
  const stationId = "206000532";
  const point = { x: pangyoPoint[0], y: pangyoPoint[1] };
  const detailRequests: string[] = [];
  let stale = false;
  await page.route("**/api/v2/stations/tiles/14/*/*", (route) => {
    const [, , zoom, x, y] = new URL(route.request().url()).pathname.split("/").slice(-5).map(Number);
    return route.fulfill({
      json: {
        tile: { zoom, x, y },
        status: "READY",
        source: "GBIS",
        coordinateSystem: "WGS84",
        stations:
          x === 13976 && y === 6354
            ? [
                {
                  stationId,
                  name: "판교역 API",
                  point,
                  mobileNo: "07489",
                  regionName: "성남",
                  direction: "정자역",
                  salmonRouteIds: [routeId],
                },
              ]
            : [],
        baselineDate: "2026-10-20",
        verifiedAt: null,
      },
    });
  });
  const arrivals = createStationArrivalsData(
    new URL(
      `http://localhost/api/v2/stations/${stationId}/arrivals?routeId=${routeId}&referenceVersionId=1&stopSequence=11`,
    ),
    Date.parse(observedAt),
    [{ vehicleId: "bus-1", remainingStops: 2, forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9 } }],
  );
  const arrival = arrivals.routes[0]!.arrivals[0]!;
  await page.route(`**/api/v2/stations/${stationId}`, (route) => {
    detailRequests.push(new URL(route.request().url()).pathname);
    return route.fulfill({
      json: {
        stationId,
        name: "판교역 API",
        point,
        coordinateSystem: "WGS84",
        regionName: "성남",
        direction: "정자역",
        notice: null,
        generatedAt: arrivals.generatedAt,
        status: stale ? "UNAVAILABLE" : arrivals.status,
        reason: stale ? "STALE" : arrivals.reason,
        snapshot: arrivals.snapshot,
        refresh: arrivals.refresh,
        routes: [
          {
            routeId,
            name: "3330",
            destinationName: "정자역",
            routeTypeCode: 11,
            supported: true,
            service: {
              routeStatus: "FORECAST_READY",
              referenceVersionId: "1",
              stopSequence: 11,
              direction: "UP",
              matchStatus: "MATCHED",
              schedule: null,
              scheduleWindow: "UNKNOWN",
            },
            status: stale ? "NO_PREDICTION" : "READY",
            reason: stale ? "NO_APPROACHING_VEHICLE" : null,
            arrivals: stale
              ? []
              : [
                  {
                    ...arrival,
                    positionSource: "BOARD",
                    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9, expectedSeats: null },
                  },
                ],
          },
          {
            routeId: "200000002",
            name: "101",
            destinationName: null,
            routeTypeCode: null,
            supported: false,
            service: null,
            status: "READY",
            reason: null,
            arrivals: [
              {
                ...arrival,
                eta: {
                  ...arrival.eta,
                  expectedArrivalAt: new Date(Date.parse(arrivals.generatedAt) + 45_000).toISOString(),
                  remainingSeconds: 45,
                },
                positionSource: "ARRIVAL",
                forecast: null,
              },
            ],
          },
          ...[
            { name: "602-2B", routeTypeCode: 13 },
            { name: "32", routeTypeCode: 30 },
            { name: "5000", routeTypeCode: 53 },
            { name: "판타G버스", routeTypeCode: 13 },
          ].map(({ name, routeTypeCode }, index) => ({
            routeId: String(200000003 + index),
            name,
            routeTypeCode,
            supported: false,
            service: null,
            arrivals: [],
          })),
        ],
      },
    });
  });
  await page.goto("/");
  const pin = page.getByRole("button", { name: "판교역 API 정류장", exact: true });
  await expect(pin).toBeVisible();
  expect(detailRequests).toEqual([]);
  await pin.press("Enter");
  const sheet = page.getByRole("region", { name: "정류장 노선 안내" });
  const supported = sheet.getByRole("link", { name: /^3330/ });
  await expect(supported).toContainText("2분 남음");
  await expect(supported.getByText("2정류장 전", { exact: true })).toBeVisible();
  await expect(supported.getByText("탑승 확률 높음", { exact: true })).toBeVisible();
  await expect(supported).toHaveAttribute("href", `/routes/${routeId}?direction=UP&stopId=${stationId}`);
  await expect(sheet.getByText("101", { exact: true })).toBeVisible();
  await expect(sheet.getByText("곧도착", { exact: true })).toBeVisible();
  for (const [name, color] of [
    ["3330", "rgb(252, 237, 231)"],
    ["602-2B", "rgb(234, 241, 252)"],
    ["32", "rgb(230, 242, 234)"],
    ["5000", "rgb(225, 244, 251)"],
    ["101", "rgb(237, 237, 235)"],
  ] as const) {
    await expect(sheet.getByText(name, { exact: true })).toHaveCSS("background-color", color);
  }
  for (const width of [425, 390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    const eta = (await supported.getByText("2분 남음", { exact: true }).boundingBox())!;
    const distance = (await supported.getByText("2정류장 전", { exact: true }).boundingBox())!;
    expect(eta.x).toBe(distance.x);
    expect(distance.y).toBeGreaterThan(eta.y + eta.height);
    for (const name of ["602-2B", "판타G버스"]) {
      const badge = sheet.getByText(name, { exact: true });
      await expect(badge).toBeVisible();
      expect(await badge.evaluate((element) => element.scrollWidth)).toBeLessThanOrEqual(
        await badge.evaluate((element) => element.clientWidth),
      );
    }
  }
  await expect(sheet.getByRole("link", { name: /^101/ })).toHaveCount(0);
  expect(detailRequests).toEqual([`/api/v2/stations/${stationId}`]);
  expect(api.arrivalRequests).toHaveLength(0);
  expect(api.requests.watch).toBe(0);

  await sheet.getByRole("button", { name: "정류장 안내 닫기", exact: true }).click();
  const nav = page.getByRole("navigation", { name: "주요 메뉴" });
  const network = page.getByTestId("home-route-network");
  await nav.getByRole("link", { name: "노선도", exact: true }).click();
  await expect(pin).toHaveCount(0);
  await page.clock.runFor(64);
  await expect(network).toBeVisible();
  await page.reload();
  await expect(page.getByRole("list", { name: "서비스 중 노선" })).toBeVisible();
  await expect(page.getByTestId("naver-map-test-double")).toBeVisible();
  await page.clock.runFor(64);
  await expect(network).toBeVisible();
  await expect(pin).toHaveCount(0);

  const favorites = nav.getByRole("link", { name: "즐겨찾기", exact: true });
  await favorites.click();
  const tabSheet = page.getByRole("region", { name: "즐겨찾기 메뉴", exact: true });
  await tabSheet.getByRole("slider", { name: "홈 정보 시트 높이", exact: true }).press("Home");
  await naverMap.setZoom(12);
  await expect(pin).toHaveCount(0);
  await naverMap.setZoom(14);
  await naverMap.panToPoint(pangyoPoint);
  await pin.click();
  await expect(sheet.getByRole("heading", { name: "판교역 API", exact: true })).toBeVisible();
  await expect(supported).toContainText("2분 남음");
  await sheet.getByRole("button", { name: "정류장 안내 닫기", exact: true }).click();
  await expect(tabSheet).toHaveCount(0);
  await expect(favorites).toHaveAttribute("aria-current", "page");
  await favorites.click();
  await expect(tabSheet).toHaveAttribute("data-snap", "summary");
  stale = true;
  await pin.click();
  await expect(supported).toContainText("도착 정보 없음");
  await expect(supported.getByText("탑승 확률 높음", { exact: true })).toHaveCount(0);
});

test("가까운 정류장의 표시는 노선 수나 선택 상태와 무관하며 확대하면 핀이 모두 보인다", async ({ page, naverMap }) => {
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({
      json: {
        generatedAt: observedAt,
        events: [],
        stops: [
          { id: "10001", name: "공원 앞", point: pangyoPoint, routes: [] },
          {
            id: "10002",
            name: "공원 건너편",
            point: [127.111176, 37.394761],
            routes: [{ routeId, routeName: "3330", direction: "UP", terminalName: "테스트 종점" }],
          },
        ],
      },
    }),
  );
  await page.goto("/");
  const park = page.getByRole("button", { name: "공원 앞 정류장", exact: true });
  const across = page.getByRole("button", { name: "공원 건너편 정류장", exact: true });
  await expect(park).toBeVisible();
  await expect(across).toHaveCount(0);
  await expect(page.getByRole("button", { name: "정류장 2곳 확대", exact: true })).toHaveCount(0);
  expect((await naverMap.snapshot()).zoom).toBe(14);

  await naverMap.setZoom(15);
  await expect(park).toBeVisible();
  await expect(across).toBeVisible();
  const nearPin = (await park.boundingBox())!;
  const oppositePin = (await across.boundingBox())!;
  expect(Math.abs(nearPin.x - oppositePin.x)).toBeGreaterThanOrEqual(nearPin.width);
  await naverMap.setZoom(17);
  await expect(park.getByText("공원 앞", { exact: true })).toBeVisible();
  await expect(across.getByText("공원 건너편", { exact: true })).toBeVisible();
  await across.press("Enter");
  await expect(across).toHaveAttribute("aria-pressed", "true");
  await expect(across.getByText("공원 건너편", { exact: true })).toBeVisible();
  await naverMap.setZoom(14);
  await expect(park).toBeVisible();
  await expect(across).toHaveCount(0);
});

test("홈 지도에서 정류장의 방면별 도착 시간을 보고 해당 판정보드를 연다", async ({ page, naverMap, api }) => {
  const stop = {
    id: "20011",
    name: "하행 중앙역",
    point: pangyoPoint,
    routes: [
      { routeId, routeName: "3330", terminalName: "테스트 기점", direction: "DOWN" },
      { routeId: "200000002", routeName: "9300", terminalName: "서울역", direction: "UP" },
    ],
  };
  const arrivals = createStationArrivalsData(
    new URL(`http://localhost/api/v2/stations/20011/arrivals?routeId=${routeId}&referenceVersionId=1&stopSequence=23`),
    Date.parse(observedAt),
    [
      {
        vehicleId: "bus-3",
        remainingStops: 2,
        forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 5 },
      },
    ],
  );
  const down = arrivals.routes[0]!;
  arrivals.routes.unshift({
    ...down,
    targetVisit: { ...down.targetVisit, direction: "UP" },
    arrivals: [],
  });
  api.data.arrivals = arrivals;
  await page.route("**/assets/home-events.json", (route) =>
    route.fulfill({
      json: {
        generatedAt: observedAt,
        events: [],
        stops: [stop, stop],
        routePaths: [
          [pangyoPoint, [127.112, 37.396]],
          [[127.112, 37.396], pangyoPoint],
          [pangyoPoint, [null, 37.396]],
        ],
      },
    }),
  );
  await page.goto("/");
  const pin = page.getByRole("button", { name: "하행 중앙역 정류장", exact: true });
  const label = pin.getByText("하행 중앙역", { exact: true });
  await expect(pin).toHaveCount(1);
  const network = page.getByTestId("home-route-network");
  await expect(network).toHaveCount(0);
  await expect(label).toBeHidden();
  await pin.click();
  await expect(label).toBeVisible();
  const sheet = page.getByRole("region", { name: "정류장 노선 안내", exact: true });
  await expect(sheet.getByRole("heading", { name: "하행 중앙역", exact: true })).toBeVisible();
  const navigation = page.getByRole("navigation", { name: "주요 메뉴" });
  await expect(navigation).toHaveCount(0);
  const sheetBounds = (await sheet.boundingBox())!;
  const pinBounds = (await pin.boundingBox())!;
  const labelBounds = (await label.boundingBox())!;
  const viewportHeight = await page.evaluate(() => window.visualViewport?.height ?? window.innerHeight);
  expect(Math.abs(sheetBounds.y - viewportHeight * 0.4)).toBeLessThan(1);
  expect(sheetBounds.y + sheetBounds.height).toBeCloseTo(viewportHeight, 0);
  expect(labelBounds.y).toBeGreaterThan(pinBounds.y + pinBounds.height);
  expect(labelBounds.x + labelBounds.width / 2).toBeCloseTo(pinBounds.x + pinBounds.width / 2, 0);
  expect(labelBounds.y + labelBounds.height).toBeLessThan(sheetBounds.y);
  const matchingRoute = sheet.getByRole("link", { name: /^3330/ });
  await expect(matchingRoute).toContainText("2분 남음");
  await expect(matchingRoute.getByText("2정류장 전", { exact: true })).toBeVisible();
  await expect(matchingRoute.getByText("탑승 확률 높음", { exact: true })).toBeVisible();
  await expect(sheet.getByText("남은 시간", { exact: true })).toHaveCount(0);
  await expect(matchingRoute).not.toContainText("방면");
  const otherRoute = sheet.getByRole("link", { name: /^9300/ });
  await expect(otherRoute).toContainText("도착 정보 없음");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    const eta = (await matchingRoute.getByText("2분 남음", { exact: true }).boundingBox())!;
    const missing = (await otherRoute.getByText("도착 정보 없음", { exact: true }).boundingBox())!;
    expect(eta.x).toBeCloseTo(missing.x, 0);
  }
  expect(api.arrivalRequests.map((url) => url.pathname + url.search)).toEqual(["/api/v2/stations/20011/arrivals"]);
  await sheet.getByRole("button", { name: "정류장 안내 닫기" }).click();
  await expect(sheet).toHaveCount(0);
  await expect(pin).toHaveAttribute("aria-pressed", "false");
  await expect(navigation).toBeVisible();
  await pin.click();
  const handle = sheet.getByRole("slider");
  await handle.press("End");
  await expect(sheet).toHaveAttribute("data-snap", "full");
  await handle.press("Home");
  await expect(sheet).toHaveCount(0);
  await expect(pin).toHaveAttribute("aria-pressed", "false");
  await expect(navigation).toBeVisible();
  await pin.click();
  const grip = (await handle.boundingBox())!;
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
  await page.mouse.down();
  await page.mouse.move(grip.x + grip.width / 2, viewportHeight - 5, { steps: 10 });
  await page.mouse.up();
  await expect(sheet).toHaveCount(0);
  await expect(pin).toHaveAttribute("aria-pressed", "false");
  await expect(navigation).toBeVisible();
  await pin.click();
  await page.getByTestId("naver-map-test-double").click({ position: { x: 10, y: 90 } });
  await expect(pin).toHaveAttribute("aria-pressed", "false");
  await expect(label).toBeHidden();
  await expect(sheet).toHaveCount(0);
  await expect(navigation).toBeVisible();
  await pin.click();
  await naverMap.setZoom(12);
  await expect(pin).toHaveCount(0);
  await expect(network).toHaveCount(0);
  const canvas = await page.getByTestId("naver-map-test-double").elementHandle();
  const board = page.getByRole("region", { name: "판정보드", exact: true });
  const routeMap = page.getByRole("region", { name: "노선 지도", exact: true });
  const readyGeometry = api.data.geometry;
  api.data.geometry = {
    ...readyGeometry,
    status: "UNAVAILABLE",
    reason: "GENERATION_PENDING",
    points: [],
    paths: null,
  };
  let releaseGeometry!: () => void;
  const geometryPending = new Promise<void>((resolve) => {
    releaseGeometry = resolve;
  });
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname === apiPaths.geometry) await geometryPending;
    await route.fallback();
  });
  const initialGeometry = page.waitForResponse((response) => new URL(response.url()).pathname === apiPaths.geometry);
  try {
    await matchingRoute.click();
    await expect(page).toHaveURL(`/routes/${routeId}?direction=DOWN&stopId=20011`);
    await expect(board).toHaveAttribute("data-entering-from-map", "false");
    expect((await board.boundingBox())!.height).toBeCloseTo(viewportHeight * 0.6, 0);
    await expect(routeMap).toHaveAttribute("aria-busy", "true");
    await expect(routeMap.getByRole("status")).toHaveCount(0);
    await expect(page.getByTestId("naver-polyline")).toHaveCount(0);
    expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  } finally {
    releaseGeometry();
  }
  await (await initialGeometry).finished();
  await page.clock.runFor(64);
  await expect(routeMap).toHaveAttribute("aria-busy", "false");
  await expect(routeMap.getByRole("status")).toHaveCount(0);
  await expect(page.getByTestId("naver-polyline")).toHaveCount(0);
  api.data.geometry = readyGeometry;
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect(page.getByTestId("naver-polyline")).toHaveCount(2);
  expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
  const list = page.getByRole("region", { name: "판정보드 정류장 목록", exact: true });
  await expect(list.locator("li[data-stop-sequence='23'] button[aria-expanded]")).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  await page.getByRole("link", { name: "노선 선택으로 돌아가기" }).click();
  await expect(page).toHaveURL("/");
  await expect(sheet.getByRole("heading", { name: "하행 중앙역", exact: true })).toBeVisible();
  expect(await canvas?.evaluate((node) => node.isConnected)).toBe(true);
});

test("탭 시트는 지도만 남도록 닫히고 탭을 반복해서 누르면 한 단계씩 오르내린다", async ({ page, naverMap, api }) => {
  const route = api.data.routes.routes[0]!;
  api.data.routes.routes = Array.from({ length: 10 }, (_, index) => ({
    ...route,
    id: String(200000001 + index),
    displayName: String(3000 + index),
  }));
  await page.goto("/route-map");
  const map = page.getByRole("region", { name: "지도", exact: true });
  const sheet = page.getByRole("region", { name: "서비스 중인 노선", exact: true });
  const sheetElement = page.locator("#home-bottom-sheet");
  const handle = sheet.getByRole("slider");
  const list = sheet.getByRole("list", { name: "서비스 중 노선" });
  const navigation = page.getByRole("navigation", { name: "주요 메뉴" });
  const routeTab = navigation.getByRole("link", { name: "노선도" });
  async function dragHandleTo(y: number) {
    const grip = await handle.boundingBox();
    if (!grip) throw new Error("드래그할 시트 손잡이가 없습니다.");
    const x = grip.x + grip.width / 2;
    await page.mouse.move(x, grip.y + grip.height / 2);
    await page.mouse.down();
    await page.mouse.move(x, y, { steps: 10 });
    await page.clock.runFor(100);
    await page.mouse.up();
  }

  await expect(list).toBeVisible();
  await expect(page.getByTestId("naver-map-test-double")).toBeVisible();
  const mapBounds = await map.boundingBox();
  const camera = await naverMap.snapshot();
  const initialHeight = (await sheet.boundingBox())!.height;
  const body = sheet.getByRole("region", { name: "선택한 탭 내용", exact: true });
  const bodyBounds = (await body.boundingBox())!;
  const navBounds = (await navigation.boundingBox())!;
  expect(bodyBounds.y + bodyBounds.height).toBeGreaterThan(navBounds.y + navBounds.height);
  await body.evaluate((element) => element.scrollTo({ top: element.scrollHeight }));
  const lastCard = (await list.getByRole("link").last().boundingBox())!;
  expect(lastCard.y + lastCard.height).toBeLessThan(navBounds.y);
  const summaryGrip = await handle.boundingBox();
  if (!summaryGrip) throw new Error("요약 높이의 시트 손잡이가 없습니다.");
  await dragHandleTo(842);
  await expect(sheetElement).toHaveAttribute("data-snap", "collapsed");
  await expect(sheet).toHaveCount(0);
  await expect(sheetElement).toHaveCSS("height", "0px");
  await routeTab.click();
  await expect(sheet).toHaveAttribute("data-snap", "summary");
  expect((await sheet.boundingBox())!.height).toBeCloseTo(initialHeight, 0);
  await handle.press("End");
  await expect(sheet).toHaveAttribute("data-snap", "full");
  await sheet.getByRole("button", { name: "서비스 중인 노선 닫기", exact: true }).click();
  await expect(sheetElement).toHaveAttribute("data-snap", "collapsed");
  await expect(routeTab).toBeFocused();
  for (const snap of ["summary", "detail", "full"]) {
    await routeTab.click();
    await expect(sheetElement).toHaveAttribute("data-snap", snap);
  }
  await handle.click();
  await expect(sheetElement).toHaveAttribute("data-snap", "detail");
  for (const snap of ["summary", "collapsed", "summary"]) {
    await routeTab.click();
    await expect(sheetElement).toHaveAttribute("data-snap", snap);
  }

  await handle.press("End");
  await routeTab.click();
  await expect(sheetElement).toHaveAttribute("data-snap", "detail");
  await dragHandleTo(summaryGrip.y + summaryGrip.height / 2);
  await expect(sheetElement).toHaveAttribute("data-snap", "summary");
  await routeTab.click();
  await expect(sheetElement).toHaveAttribute("data-snap", "detail");
  await expect(page).toHaveURL("/route-map");
  expect(await map.boundingBox()).toEqual(mapBounds);
  expect(await naverMap.snapshot()).toEqual(camera);

  for (const [tab, label, path] of [
    ["즐겨찾기", "즐겨찾기 메뉴", "/favorites"],
    ["마이페이지", "마이페이지", "/mypage"],
    ["홈", "홈 정보", "/"],
  ] as const) {
    const navigationTab = navigation.getByRole("link", { name: tab, exact: true });
    await navigationTab.click();
    const tabSheet = page.getByRole("region", { name: label, exact: true });
    await expect(tabSheet.getByRole("button", { name: `${label} 닫기`, exact: true })).toHaveCount(0);
    await tabSheet.getByRole("slider", { name: "홈 정보 시트 높이", exact: true }).press("Home");
    await expect(sheetElement).toHaveAttribute("data-snap", "collapsed");
    await expect(tabSheet).toHaveCount(0);
    await expect(page).toHaveURL(path);
    await expect(navigationTab).toBeFocused();
    await navigationTab.click();
    await expect(tabSheet).toHaveAttribute("data-snap", "summary");
    await expect(page).toHaveURL(path);
  }
});

test("탭 시트가 요약 높이에서 접힐 때 아래로 내려간 뒤 사라진다", async ({ page }) => {
  await page.goto("/route-map");
  const sheet = page.getByRole("region", { name: "서비스 중인 노선", exact: true });
  const tab = page.getByRole("link", { name: "노선도", exact: true });
  for (const snap of ["detail", "full", "detail", "summary"]) {
    await tab.click();
    await expect(sheet).toHaveAttribute("data-snap", snap);
  }
  await page.emulateMedia({ reducedMotion: "no-preference" });

  const slide = await tab.evaluate((element: HTMLAnchorElement) => {
    const sheet = document.getElementById("home-bottom-sheet")!;
    const topBefore = sheet.getBoundingClientRect().top;
    element.click();
    const [animation] = sheet.parentElement!.getAnimations();
    const endTime = animation?.effect?.getComputedTiming().endTime;
    if (!animation || typeof endTime !== "number") return null;
    animation.currentTime = endTime / 2;
    const { top, height } = sheet.getBoundingClientRect();
    return { movingDown: top > topBefore, stillVisible: height > 0 && top < window.innerHeight };
  });
  expect(slide).toEqual({ movingDown: true, stillVisible: true });
  await expect(sheet).toBeHidden();
  await tab.click();
  await expect(sheet).toHaveAttribute("data-snap", "summary");
  await sheet.evaluate((element) =>
    Promise.allSettled(element.parentElement?.getAnimations().map((animation) => animation.finished) ?? []),
  );

  const closeButton = await sheet.getByRole("button", { name: "서비스 중인 노선 닫기", exact: true }).elementHandle();
  const reopenedDuringClose = await tab.evaluate(async (routeTab: HTMLAnchorElement, close) => {
    if (!(close instanceof HTMLButtonElement)) return false;
    close.click();
    const [closing] = close.closest("section")?.parentElement?.getAnimations() ?? [];
    if (!closing) return false;
    const settled = Promise.allSettled([closing.finished]);
    routeTab.click();
    await settled;
    return true;
  }, closeButton);
  expect(reopenedDuringClose).toBe(true);
  await expect(sheet).toHaveAttribute("data-snap", "summary");
  await expect(sheet).toBeInViewport();
});

test("홈 시트가 닫히는 중 로고로 홈에 다시 들어오면 시트를 볼 수 있다", async ({ page }) => {
  await page.goto("/");
  const sheet = page.getByRole("region", { name: "홈 정보", exact: true });
  const tab = page.getByRole("link", { name: "홈", exact: true });
  const brand = page.getByRole("link", { name: "연어버스 홈으로 이동", exact: true });
  for (const snap of ["detail", "full", "detail", "summary"]) {
    await tab.click();
    await expect(sheet).toHaveAttribute("data-snap", snap);
  }
  await page.emulateMedia({ reducedMotion: "no-preference" });

  const brandLink = await brand.elementHandle();
  const reenteredDuringClose = await tab.evaluate(async (homeTab: HTMLAnchorElement, logo) => {
    const surface = document.getElementById("home-bottom-sheet")?.parentElement;
    if (!surface || !(logo instanceof HTMLAnchorElement)) return false;
    homeTab.click();
    const [closing] = surface.getAnimations();
    if (!closing || closing.playState === "finished") return false;
    logo.click();
    await Promise.allSettled([closing.finished]);
    return true;
  }, brandLink);

  expect(reenteredDuringClose).toBe(true);
  await expect(sheet).toHaveAttribute("data-snap", "summary");
  await expect(sheet).toBeInViewport();
  await tab.click();
  await expect(sheet).toHaveAttribute("data-snap", "detail");
});
