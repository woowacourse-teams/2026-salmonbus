import { routeId } from "./fixtures/mock-data";
import { expect, test } from "./fixtures/naver-map";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "no-preference" });

for (const screen of [
  { name: "홈", path: "/", locationLabel: "현재 위치로 이동" },
  { name: "판정보드", path: `/routes/${routeId}`, locationLabel: "현재 위치 보기" },
]) {
  test(`${screen.name} 지도는 회전할 때 나침반을 표시하고 같은 위치에서 북쪽으로 돌아간다`, async ({
    page,
    naverMap,
  }) => {
    await page.goto(screen.path);
    await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
    await page.clock.runFor(64);
    const location = page.getByRole("button", { name: screen.locationLabel, exact: true });
    const compass = page.getByRole("button", { name: "지도를 북쪽으로 돌리기" });
    await expect(location).toBeVisible();
    await expect(compass).toHaveCount(0);

    const northUpCamera = await naverMap.snapshot();
    await naverMap.setRotation(270);
    await page.clock.runFor(32);
    await expect(compass).toBeVisible();
    const pointer = compass.getByTestId("compass-needle");
    const needle = await pointer.evaluate((element) => {
      const { a, b } = new DOMMatrixReadOnly(getComputedStyle(element).transform);
      return { a, b };
    });
    expect(needle.a).toBeCloseTo(0);
    expect(needle.b).toBeCloseTo(1);
    const compassBox = (await compass.boundingBox())!;
    const locationBox = (await location.boundingBox())!;
    expect(compassBox.x).toBeCloseTo(locationBox.x);
    expect(compassBox.y + compassBox.height).toBeLessThan(locationBox.y);

    await compass.click();
    await page.clock.runFor(128);
    const turning = await naverMap.getRotation();
    expect(turning).toBeGreaterThan(-90);
    expect(turning).toBeLessThan(0);
    await page.clock.runFor(160);
    await expect(compass).toHaveCount(0);
    expect(await naverMap.getRotation()).toBe(0);
    expect(await naverMap.snapshot()).toEqual(northUpCamera);
  });
}

test("회전 중인 지도에서 나침반 화살표는 각도 경계를 지나도 즉시 북쪽을 가리킨다", async ({ page, naverMap }) => {
  await page.goto("/");
  await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
  await page.clock.runFor(64);
  const compass = page.getByRole("button", { name: "지도를 북쪽으로 돌리기" });
  const pointer = compass.getByTestId("compass-needle");

  for (const rotation of [45, 179, -179, -120]) {
    await naverMap.setRotation(rotation);
    await page.clock.runFor(32);
    const angle = await pointer.evaluate((element) => {
      const { a, b } = new DOMMatrixReadOnly(getComputedStyle(element).transform);
      return (Math.atan2(b, a) * 180) / Math.PI;
    });
    expect(angle).toBeCloseTo(-rotation, 2);
  }
});

test("나침반을 키보드로 누를 때 위치 요청을 시작하거나 포커스를 잃지 않는다", async ({ page, naverMap }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "geolocation", { value: { getCurrentPosition() {} } });
  });
  await page.goto("/");
  await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
  await page.clock.runFor(64);
  const compass = page.getByRole("button", { name: "지도를 북쪽으로 돌리기" });
  const location = page.getByRole("button", { name: "현재 위치로 이동" });

  await naverMap.setRotation(45);
  await page.clock.runFor(32);
  await compass.focus();
  await page.keyboard.down("Enter");
  await page.clock.runFor(600);
  await page.keyboard.down("Enter");
  await expect(compass).toBeFocused();
  await expect(location).toBeEnabled();
  await page.keyboard.up("Enter");
  await page.clock.runFor(300);
  await expect(compass).toHaveCount(0);
  await expect(location).toBeFocused();

  await location.click();
  await expect(page.getByRole("button", { name: "현재 위치 확인 중" })).toBeDisabled();
  await naverMap.setRotation(45);
  await page.clock.runFor(32);
  await compass.press("Enter");
  await page.clock.runFor(300);
  await expect(compass).toHaveCount(0);
  await expect(page.getByRole("group", { name: "지도 도구" })).toBeFocused();
});

test("판정보드에서 북쪽 복귀 중 지도 SDK가 실패해도 정류장 목록을 계속 볼 수 있다", async ({ page, naverMap }) => {
  const errors: Error[] = [];
  page.on("pageerror", (error) => errors.push(error));
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByTestId("naver-map-test-double")).toHaveCount(1);
  await page.clock.runFor(64);
  await naverMap.setRotation(45);
  await page.clock.runFor(32);
  await page.evaluate(() => {
    window.naver!.maps.Map.prototype.setRotation = () => {
      throw new Error("지도 SDK에서 회전할 수 없습니다");
    };
  });
  await page.getByRole("button", { name: "지도를 북쪽으로 돌리기" }).click();
  await page.clock.runFor(300);
  await expect(page.getByText("지도를 불러오지 못했어요", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "판정보드 정류장 목록" })).toBeVisible();
  expect(errors).toHaveLength(0);
});
