import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures/auth";

const POLICY_LINK = "개인정보 처리방침";
const COOKIE_TABLE = "이름, 만드는 곳, 목적, 담는 정보, 보관 기간 표";

const policyTitle = (page: Page) => page.getByRole("heading", { level: 1, name: "개인정보 처리방침", exact: true });
const firstSection = (page: Page) =>
  page.getByRole("heading", { level: 2, name: "1. 개인정보의 처리 목적", exact: true });
const transferSection = (page: Page) =>
  page.getByRole("heading", { level: 2, name: "8. 개인정보의 국외 이전", exact: true });
const backLink = (page: Page) => page.getByRole("link", { name: "이전 화면으로 돌아가기", exact: true });
const currentEditionLink = (page: Page) =>
  page.getByRole("link", { name: "지금 적용 중인 처리방침 보기", exact: true });
const myPagePolicyLink = (page: Page) =>
  page.getByRole("region", { name: "개인정보" }).getByRole("link", { name: POLICY_LINK, exact: true });

async function expectCurrentEdition(page: Page) {
  await expect(policyTitle(page)).toBeVisible();
  await expect(page.getByText(/^2026년 11월 4일부터 적용/)).toBeVisible();
  await expect(page.getByText("시행일: 2026년 11월 4일", { exact: true })).toBeVisible();
  await expect(firstSection(page)).toBeVisible();
  await expect(page.getByRole("region", { name: "다른 판" })).toHaveCount(0);
}

// React는 늦게 받은 화면을 대체 화면 위에 타이머로 드러내서, 멈춘 시계에서는 처리방침이 열리지 않는다.
test.beforeEach(async ({ page }) => {
  await page.clock.resume();
});

function privacyScriptRequests(page: Page): string[] {
  const requests: string[] = [];
  page.on("request", (request) => {
    const { pathname } = new URL(request.url());
    if (request.resourceType() === "script" && pathname.includes("privacy")) requests.push(pathname);
  });
  return requests;
}

test("주소로 바로 들어오면 지금 판의 제목, 시행일, 본문을 보여 주고 새로고침해도 같다", async ({ page }) => {
  await page.goto("/privacy");
  await expectCurrentEdition(page);

  await page.reload();
  await expectCurrentEdition(page);
});

test("시행일 주소로 들어오면 그 판을 보여 주고, 지금 판이면 지금 판으로 가는 링크는 없다", async ({ page }) => {
  await page.goto("/privacy/2026-11-04");

  await expectCurrentEdition(page);
  await expect(currentEditionLink(page)).toHaveCount(0);
});

for (const date of ["2025-01-01", "latest"]) {
  test(`없는 판의 주소(${date})로 들어오면 안내와 함께 지금 판으로 가는 링크를 보여 준다`, async ({ page }) => {
    await page.goto(`/privacy/${date}`);
    await expect(page.getByText("이 주소의 처리방침을 찾지 못했어요.", { exact: true })).toBeVisible();
    await expect(firstSection(page)).toHaveCount(0);

    await currentEditionLink(page).click();
    await expect(page).toHaveURL("/privacy");
    await expect(firstSection(page)).toBeVisible();
  });
}

test("목차를 누르면 주소와 방문 기록을 바꾸지 않고 그 절로 옮겨 가서, 뒤로 가기 한 번에 마이페이지로 돌아간다", async ({
  page,
}) => {
  await page.goto("/mypage");
  await myPagePolicyLink(page).click();
  await expect(policyTitle(page)).toBeVisible();

  await page.getByRole("link", { name: "개인정보의 국외 이전", exact: true }).click();
  await expect(transferSection(page)).toBeInViewport();
  await expect(transferSection(page)).toBeFocused();
  await expect(page).toHaveURL("/privacy");

  await page.goBack();
  await expect(page).toHaveURL("/mypage");
});

test("주소에 절 아이디(#)를 붙여 들어오면 그 절을 보여 준다", async ({ page }) => {
  await page.goto("/privacy#8-개인정보의-국외-이전");

  await expect(transferSection(page)).toBeInViewport();
});

test("마이페이지 아래쪽에서 연 처리방침은 맨 위부터 보여 주고, 돌아가기로 마이페이지에 돌아간다", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.goto("/mypage");
  const privacy = page.getByRole("region", { name: "개인정보" });
  const participation = privacy.getByRole("switch", { name: "서비스 개선에 참여하기" });

  // 처음 열 때는 짧은 대체 화면이 스크롤을 0으로 당겨서, 코드를 받아 둔 두 번째 열기에서 확인한다.
  for (const visit of [1, 2]) {
    await expect(participation).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    expect(await page.evaluate(() => window.scrollY), `${visit}번째 열기 전 마이페이지 스크롤`).toBeGreaterThan(0);
    await privacy.getByRole("link", { name: POLICY_LINK }).click();
    await expect(page).toHaveURL("/privacy");
    await expect(policyTitle(page)).toBeInViewport();
    expect(await page.evaluate(() => window.scrollY), `${visit}번째 열기의 처리방침 스크롤`).toBe(0);

    await backLink(page).click();
    await expect(page).toHaveURL("/mypage");
  }
});

test("로그인 화면에서 연 처리방침은 돌아가기로 같은 로그인 화면 주소에 돌아간다", async ({ page }) => {
  await page.goto("/login?returnTo=%2Fmypage");
  await page.getByRole("link", { name: POLICY_LINK, exact: true }).click();
  await expect(policyTitle(page)).toBeVisible();

  await backLink(page).click();
  await expect(page).toHaveURL("/login?returnTo=%2Fmypage");
  await expect(page.getByRole("button", { name: "카카오로 계속하기", exact: true })).toBeVisible();
});

test("처리방침으로 바로 들어와서 돌아가기를 누르면 홈으로 간다", async ({ page }) => {
  await page.goto("/privacy");
  await expect(policyTitle(page)).toBeVisible();

  await backLink(page).click();
  await expect(page).toHaveURL("/");
});

test("홈에는 처리방침 링크가 없고, 처리방침 코드는 마이페이지에서 처리방침을 열 때 한 번 받는다", async ({ page }) => {
  const requests = privacyScriptRequests(page);
  await page.goto("/");
  await expect(page.getByRole("link", { name: "마이페이지", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: POLICY_LINK, exact: true })).toHaveCount(0);

  await page.getByRole("link", { name: "마이페이지", exact: true }).click();
  await expect(myPagePolicyLink(page)).toBeVisible();
  expect(requests).toEqual([]);

  await myPagePolicyLink(page).click();
  await expect(firstSection(page)).toBeVisible();
  expect(requests).toHaveLength(1);
});

test("처리방침 코드를 받지 못하면 오류 화면을 보여 주고, 다시 시도하면 처리방침을 연다", async ({ page }) => {
  const blockPrivacyScript = /\/assets\/js\/privacy\.[^/]+\.js$/;
  await page.route(blockPrivacyScript, (route) => route.abort());
  await page.goto("/mypage");
  await myPagePolicyLink(page).click();
  await expect(page.getByText("앗, 문제가 생겼어요", { exact: true })).toBeVisible();

  await page.unroute(blockPrivacyScript);
  await page.getByRole("button", { name: "다시 시도하기", exact: true }).click();
  await expect(firstSection(page)).toBeVisible();
});

test("320px 화면에서도 페이지는 옆으로 밀리지 않고, 넓은 표만 자기 칸 안에서 옆으로 넘긴다", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/privacy");
  await expect(firstSection(page)).toBeVisible();

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const cookieTable = page.getByRole("region", { name: COOKIE_TABLE, exact: true });
  expect(await cookieTable.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
  await cookieTable.focus();
  await expect(cookieTable).toBeFocused();
});
