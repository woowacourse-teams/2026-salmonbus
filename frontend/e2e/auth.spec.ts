import { test, expect, testMember, redirectAuth } from "./fixtures/auth";
import { selectRoute } from "./utils/route-select";

test.use({ viewport: { width: 390, height: 844 }, reducedMotion: "reduce" });

test("인증 API가 꺼져 있어도 노선을 검색하고 판정보드에 진입한다", async ({ page, auth }) => {
  auth.enabled = false;
  await page.goto("/login");
  await expect(page.getByText("로그인 기능을 준비 중이에요. 노선 검색은 계속 이용할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "카카오로 계속하기" })).toBeDisabled();
  await page.getByRole("link", { name: "돌아가기" }).click();
  await selectRoute(page);
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
  await page.getByText(`${testMember.nickname}님`, { exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await page.getByRole("link", { name: "닉네임 수정" }).click();
  const input = page.getByRole("textbox", { name: "닉네임", exact: true });
  await input.fill("연어");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByText("이미 사용 중인 닉네임이에요.")).toBeVisible();
  await expect(input).toBeFocused();
  auth.nicknameError = null;
  await input.fill("새연어");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByText("새연어님", { exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("닉네임을 변경했어요");
  await page.reload();
  await expect(page.getByText("새연어님", { exact: true })).toBeVisible();
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
    await expect(page.getByRole("heading", { name: "내 정류장을 저장해 보세요", exact: true })).toBeVisible();
  });
}

// 결과별 안내 문구는 withdrawalNoticeLabelOf.test.ts가 본다.
test("Apple 탈퇴 cancelled 후 회원을 유지하고 재시도할 수 있다", async ({ page, auth, browserName }) => {
  auth.member = { ...testMember, provider: "APPLE" };
  await page.goto("/mypage");
  await page.getByRole("button", { name: "회원탈퇴", exact: true }).click();
  await expect(page.getByText("탈퇴를 완료하려면 Apple 계정으로 한 번 더 인증해야 해요.")).toBeVisible();
  await page.route("**/api/auth/apple/start?**", (route) => redirectAuth(route, "/?withdraw=cancelled", browserName));
  await page.getByRole("dialog").getByRole("button", { name: "회원탈퇴", exact: true }).click();
  await expect(page).toHaveURL("/mypage");
  await expect(page.getByRole("status").filter({ hasText: "회원탈퇴를 취소했어요" })).toBeVisible();
  await expect(page.getByText("살몽님", { exact: true })).toBeVisible();
  await expect(page.getByText("Apple 계정으로 로그인 중")).toBeVisible();
  expect(
    auth.requests.some((request) => request.path === "/api/me/withdrawal/apple" && request.csrf === "e2e-csrf"),
  ).toBe(true);
  await page.getByRole("button", { name: "회원탈퇴", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
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
  await expect(page.getByText("등록된 즐겨찾기가 없어요", { exact: true })).toBeVisible();
});
