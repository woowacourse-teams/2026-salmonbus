import { test, expect, testMember, redirectAuth } from "./fixtures/auth";
import contract from "../../contract/auth-v1.openapi.json" with { type: "json" };
import { routeButton, routeSearchInput } from "./utils/route-select";
import { routeId } from "./fixtures/mock-data";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

for (const { name, path } of [
  { name: "홈", path: "/" },
  { name: "마이페이지", path: "/mypage" },
]) {
  test(`${name}에서 로그인하면 같은 화면으로 복귀한다`, async ({ page, auth }) => {
    await page.goto("/");
    if (path === "/mypage") await page.getByRole("link", { name: "마이페이지", exact: true }).click();
    await expect(page).toHaveURL(path);
    await page.getByRole("link", { name: "로그인 / 회원가입", exact: true }).click();
    await expect(page).toHaveURL(`/login?${new URLSearchParams({ returnTo: path })}`);
    await page.getByRole("button", { name: "카카오로 계속하기" }).click();
    await expect(page).toHaveURL(path);
    await expect(page.getByText(path === "/" ? "어서오세요 연어버스입니다!" : "살몽님", { exact: true })).toBeVisible();
    expect(auth.requests.some((request) => request.path === "/api/auth/kakao/start")).toBe(true);
  });
}

test("인증 API가 꺼져 있어도 노선을 검색하고 판정보드에 진입한다", async ({ page, auth }) => {
  auth.enabled = false;
  await page.goto("/login");
  await expect(page.getByText("로그인 기능을 준비 중이에요. 노선 검색은 계속 이용할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "카카오로 계속하기" })).toBeDisabled();
  await page.getByRole("link", { name: "돌아가기" }).click();
  await routeSearchInput(page).fill("3330");
  await routeButton(page).click();
  await expect(page).toHaveURL(`/routes/${routeId}`);
});

for (const loginResult of ["failed", "cancelled"] as const) {
  test(`인증 ${loginResult} 결과는 복귀 경로를 보존하고 로그인 화면에서 안내한다`, async ({ page, auth }) => {
    auth.loginResult = loginResult;
    await page.goto("/login?returnTo=%2Fmypage");
    await page.getByRole("button", { name: "카카오로 계속하기" }).click();
    await expect(page).toHaveURL("/login?returnTo=%2Fmypage");
    if (loginResult === "failed") {
      const dialog = page.getByRole("dialog");
      await expect(dialog).toBeVisible();
      await dialog.getByRole("button", { name: "다시 로그인", exact: true }).click();
      await expect(dialog).not.toBeVisible();
    } else await expect(page.getByText("로그인을 취소했어요.")).toBeVisible();
  });
}

test("만료된 액세스 토큰은 한 번 갱신해 로그인 상태를 복원한다", async ({ page, auth }) => {
  auth.refreshable = true;
  await page.goto("/mypage");
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  expect(auth.requests.filter((request) => request.path === "/api/auth/refresh")).toHaveLength(1);
});

test("닉네임 화면에서 로그인 상태 조회 실패 후 다시 확인하면 수정 폼을 복원한다", async ({ page, auth }) => {
  auth.member = { ...testMember };
  await page.route("**/api/auth/session", (route) =>
    route.fulfill({
      status: 503,
      json: { code: "SERVICE_UNAVAILABLE", message: "SERVICE_UNAVAILABLE", requestId: "auth-e2e" },
      headers: { "Cache-Control": "no-store" },
    }),
  );
  await page.goto("/mypage/nickname");
  await expect(page.getByRole("alert")).toHaveText("로그인 상태를 확인하지 못했어요.");
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toHaveCount(0);

  await page.unroute("**/api/auth/session");
  await page.getByRole("button", { name: "다시 확인하기", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "닉네임", exact: true })).toHaveValue("살몽");
  await expect(page.getByRole("alert")).toBeEmpty();
  expect(auth.requests.some((request) => /\/api\/auth\/(kakao|apple)\/start$/.test(request.path))).toBe(false);
});

test("닉네임 중복 오류 후 입력을 수정해 저장하고 재접속해도 유지한다", async ({ page, auth }) => {
  auth.member = { ...testMember };
  auth.nicknameError = "NICKNAME_TAKEN";
  await page.goto("/mypage");
  await page.getByRole("link", { name: "닉네임 수정" }).click();
  const input = page.getByRole("textbox", { name: "닉네임", exact: true });
  await input.fill("연어");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByText("이미 사용 중인 닉네임이에요.")).toBeVisible();
  auth.nicknameError = null;
  await input.fill("새연어");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("새연어님", { exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("닉네임을 변경했어요");
  await page.reload();
  await expect(page.getByText("새연어님", { exact: true })).toBeVisible();
  expect(
    auth.requests.filter((request) => request.method === "PATCH").every((request) => request.csrf === "e2e-csrf"),
  ).toBe(true);
});

test("닉네임 저장 실패 후 같은 입력으로 재시도하면 이전 오류를 지우고 저장한다", async ({ page, auth }) => {
  auth.member = { ...testMember };
  auth.mutationFails = true;
  await page.goto("/mypage/nickname");
  const input = page.getByRole("textbox", { name: "닉네임", exact: true });
  const save = page.getByRole("button", { name: "저장", exact: true });
  const error = page.getByRole("alert");
  await input.fill("새살몽");
  await save.click();
  await expect(error).toContainText("요청을 완료하지 못했어요");
  await expect(save).toBeEnabled();

  auth.mutationFails = false;
  let finish!: () => void;
  const release = new Promise<void>((resolve) => {
    finish = resolve;
  });
  await page.route("**/api/me", async (route) => {
    await release;
    await route.fallback();
  });
  try {
    await save.click();
    await expect(page.getByRole("button", { name: "저장 중…", exact: true })).toBeDisabled();
    await expect(error).toBeEmpty();
    await expect(input).toHaveValue("새살몽");
  } finally {
    finish();
  }
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("새살몽님", { exact: true })).toBeVisible();
});

for (const action of ["로그아웃", "회원탈퇴"] as const) {
  test(`${action} 취소와 요청 실패 시 회원을 유지하고 성공 시 비회원 홈으로 돌아간다`, async ({ page, auth }) => {
    auth.member = { ...testMember };
    await page.goto("/mypage");
    await page.getByRole("button", { name: action, exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "취소", exact: true }).click();
    await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: action, exact: true }).click();
    auth.mutationFails = true;
    await page.getByRole("dialog").getByRole("button", { name: action, exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("요청을 완료하지 못했어요");
    expect(auth.member).not.toBeNull();
    auth.mutationFails = false;
    await page.getByRole("dialog").getByRole("button", { name: action, exact: true }).click();
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();
  });
}

for (const result of ["cancelled", "failed"] as const) {
  test(`Apple 탈퇴 ${result} 후 회원을 유지하고 재시도할 수 있다`, async ({ page, auth, browserName }) => {
    auth.member = { ...testMember, provider: "APPLE" };
    await page.goto("/mypage");
    await page.getByRole("button", { name: "회원탈퇴", exact: true }).click();
    await expect(page.getByText("탈퇴를 완료하려면 Apple 계정으로 한 번 더 인증해야 해요.")).toBeVisible();
    await page.route("**/api/auth/apple/start?**", (route) => redirectAuth(route, `/?withdraw=${result}`, browserName));
    await page.getByRole("dialog").getByRole("button", { name: "회원탈퇴", exact: true }).click();
    await expect(page).toHaveURL("/mypage");
    await expect(
      page.getByRole("status").filter({
        hasText: result === "cancelled" ? "회원탈퇴를 취소했어요" : "회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요.",
      }),
    ).toBeVisible();
    await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
    expect(
      auth.requests.some((request) => request.path === "/api/me/withdrawal/apple" && request.csrf === "e2e-csrf"),
    ).toBe(true);
    await page.getByRole("button", { name: "회원탈퇴", exact: true }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
  });
}

test("Apple 탈퇴 시작이 401이면 갱신 후 같은 요청을 재시도한다", async ({ page, auth, browserName }) => {
  auth.member = { ...testMember, provider: "APPLE" };
  let attempts = 0;
  await page.route("**/api/me/withdrawal/apple", async (route) => {
    attempts += 1;
    if (attempts === 1) {
      await route.fulfill({
        status: 401,
        json: contract.paths["/api/me/withdrawal/apple"].post.responses["401"].content["application/json"].examples
          .unauthenticated.value,
        headers: { "Cache-Control": "no-store" },
      });
    } else await route.fallback();
  });
  await page.route("**/api/auth/apple/start?**", (route) => redirectAuth(route, "/?withdraw=cancelled", browserName));
  await page.goto("/mypage");
  await page.getByRole("button", { name: "회원탈퇴", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "회원탈퇴", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByRole("status").filter({ hasText: "회원탈퇴를 취소했어요" })).toBeVisible();
  await expect(page.getByText("Apple 계정으로 로그인 중")).toBeVisible();
  expect(attempts).toBe(2);
  expect(auth.requests.filter((request) => request.path === "/api/auth/refresh")).toHaveLength(1);
});

test("방면·정류장이 담긴 판정보드 주소는 로그인 후에도 유지한다", async ({ page, api }) => {
  const stop = api.data.board.stops.find((candidate) => candidate.direction === "DOWN" && candidate.boardingAllowed)!;
  const returnTo = `/routes/${routeId}?direction=DOWN&stopId=${stop.stopId}`;
  await page.goto(`/login?${new URLSearchParams({ returnTo })}`);
  await page.getByRole("button", { name: "카카오로 계속하기" }).click();
  await expect(page).toHaveURL(returnTo);
  await expect(
    page.getByRole("group", { name: "운행 방향 선택" }).getByRole("button", { pressed: true }),
  ).toContainText("방면");
  await expect(
    page.locator(`[data-stop-sequence="${stop.sequence}"]`).getByRole("button", { expanded: true }).first(),
  ).toBeVisible();
});

test("갱신 토큰도 만료되면 비회원으로 복귀하고 갱신을 반복하지 않는다", async ({ page, auth }) => {
  auth.refreshable = true;
  auth.refreshFails = true;
  await page.goto("/mypage");
  await expect(page.getByRole("link", { name: "로그인 / 회원가입", exact: true })).toBeVisible();
  expect(auth.requests.filter((request) => request.path === "/api/auth/refresh")).toHaveLength(1);
});

test("닉네임 저장 중 홈으로 이동하면 늦은 응답이 마이페이지로 되돌리지 않는다", async ({ page, auth }) => {
  auth.member = { ...testMember };
  let finish!: () => void;
  const release = new Promise<void>((resolve) => {
    finish = resolve;
  });
  await page.route("**/api/me", async (route) => {
    await release;
    await route.fallback();
  });
  await page.goto("/mypage/nickname");
  await page.getByRole("textbox", { name: "닉네임", exact: true }).fill("새살몽");
  const started = page.waitForRequest((request) => request.method() === "PATCH");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await started;
  await page.getByRole("link", { name: "홈", exact: true }).click();
  const response = page.waitForResponse((response) => response.request().method() === "PATCH");
  finish();
  await (await response).finished();
  await page.clock.runFor(32);
  await expect(page).toHaveURL("/");
  await expect(page.getByText("어서오세요 연어버스입니다!")).toBeVisible();
});
