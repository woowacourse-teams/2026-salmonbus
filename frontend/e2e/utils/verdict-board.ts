import type { Page } from "@playwright/test";
import type { Direction } from "../../src/shared/api/routeForecast.types";
import { expect } from "../fixtures/api";
import { directionLabels, routeId, stopNames, targetStopNames } from "../fixtures/mock-data";

export function directionButton(page: Page, direction: Direction) {
  return page
    .getByRole("group", { name: "운행 방향 선택" })
    .getByRole("button", { name: directionLabels[direction], exact: true });
}

export function targetStop(page: Page, direction: Direction) {
  return page
    .getByRole("region", { name: "판정보드 정류장 목록", exact: true })
    .getByRole("button", { name: new RegExp(`^${targetStopNames[direction]}`) })
    .and(page.locator("button[aria-expanded]"));
}

export function targetRow(page: Page, direction: Direction) {
  return page
    .getByRole("region", { name: "판정보드 정류장 목록", exact: true })
    .locator("li[data-stop-sequence]")
    .filter({ has: page.getByRole("button", { name: new RegExp(`^${targetStopNames[direction]}`) }) });
}

export async function expectDirection(page: Page, direction: Direction) {
  const other = direction === "UP" ? "DOWN" : "UP";
  await expect(directionButton(page, direction)).toHaveAttribute("aria-pressed", "true");
  await expect(directionButton(page, other)).toHaveAttribute("aria-pressed", "false");
  // 펼침 상태를 가진 정류장 버튼만 선택하여 개수와 전체 순서를 함께 확인한다.
  await expect(
    page.getByRole("region", { name: "판정보드 정류장 목록", exact: true }).locator("button[aria-expanded]"),
  ).toHaveText(stopNames[direction].map((name) => new RegExp(`^${name}`)));
  await expect(targetStop(page, other)).toHaveCount(0);
}

// 검색에서 보드로 가는 길은 happy-path와 route-select가 보고, 보드 테스트는 판정 보드 주소로 바로 연다.
export async function openBoard(page: Page) {
  await page.goto(`/routes/${routeId}`);
  await expect(
    page
      .getByRole("heading", { name: "3330", exact: true })
      .or(page.getByText("현재는 운행시간이 아닙니다", { exact: true })),
  ).toBeVisible();
}

export function boardBackLink(page: Page) {
  return page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true });
}
