import type { Page } from "@playwright/test";
import type { Direction } from "../../src/shared/api/routeForecast.types";
import { expect } from "../fixtures/api";
import { directionLabels, stopNames, targetStopNames } from "../fixtures/mock-data";
import { selectRoute } from "./route-select";

export function directionButton(page: Page, direction: Direction) {
  return page
    .getByRole("group", { name: "운행 방향 선택" })
    .getByRole("button", { name: directionLabels[direction], exact: true });
}

export function targetStop(page: Page, direction: Direction) {
  return page.getByRole("button", { name: new RegExp(`^${targetStopNames[direction]}`) });
}

export function targetRow(page: Page, direction: Direction) {
  return page.getByRole("listitem").filter({ has: targetStop(page, direction) });
}

export async function expectDirection(page: Page, direction: Direction) {
  const other = direction === "UP" ? "DOWN" : "UP";
  await expect(directionButton(page, direction)).toHaveAttribute("aria-pressed", "true");
  await expect(directionButton(page, other)).toHaveAttribute("aria-pressed", "false");
  // 펼침 상태를 가진 정류장 버튼만 선택하여 개수와 전체 순서를 함께 확인한다.
  await expect(page.locator("button[aria-expanded]")).toHaveText(
    stopNames[direction].map((name) => new RegExp(`^${name}`)),
  );
  await expect(targetStop(page, other)).toHaveCount(0);
}

export async function openBoard(page: Page) {
  await page.goto("/");
  await selectRoute(page);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
}
