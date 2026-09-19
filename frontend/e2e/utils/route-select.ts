import type { Page } from "@playwright/test";
import { expect } from "../fixtures/api";
import { routeId } from "../fixtures/mock-data";

export function routeButton(page: Page) {
  return page.getByRole("button", { name: "3330번 노선, 테스트 기점부터 테스트 종점 구간", exact: true });
}

export async function selectRoute(page: Page) {
  await routeButton(page).click();
  await expect(page).toHaveURL(`/routes/${routeId}`);
}
