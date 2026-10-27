import type { Page } from "@playwright/test";
import { expect } from "../fixtures/api";
import { routeId } from "../fixtures/mock-data";

export function routeButton(page: Page) {
  return page.getByRole("button", { name: /^3330번 노선, (새 노선, )?테스트 기점부터 테스트 종점 구간$/ });
}

export function routeSearchEntry(page: Page) {
  return page.getByRole("textbox", { name: "번호를 입력하면 탑승 확률을 알려드려요", exact: true });
}

export function routeSearchInput(page: Page) {
  return page.getByRole("textbox", { name: "노선 번호", exact: true });
}

export function inServiceSection(page: Page) {
  return page.getByRole("region", { name: "서비스 중 노선", exact: true });
}

export function routeSearchBackLink(page: Page) {
  return page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true });
}

// 홈은 검색 화면을 먼저 그린 뒤 한 프레임 지나 주소를 바꾼다. fixture가 시계를 멈춰 두어 직접 진행시킨다.
export async function finishSearchNavigation(page: Page) {
  await page.clock.runFor(100);
  await expect(page).toHaveURL("/search");
}

export async function openRouteSearch(page: Page) {
  await routeSearchEntry(page).click();
  await finishSearchNavigation(page);
}

export async function pickRoute(page: Page) {
  await routeSearchInput(page).fill("3330");
  await routeButton(page).click();
  await expect(page).toHaveURL(`/routes/${routeId}`);
}

export async function selectRoute(page: Page) {
  await openRouteSearch(page);
  await pickRoute(page);
}
