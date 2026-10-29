import type { Locator } from "@playwright/test";
import { expect } from "../fixtures/api";

export async function expectFocusRing(target: Locator, ring: "shown" | "hidden") {
  await expect(target).toBeFocused();
  if (ring === "shown") await expect(target).not.toHaveCSS("outline-style", "none");
  else await expect(target).toHaveCSS("outline-style", "none");
}

// WebKit은 기본 설정에서 Tab으로 버튼을 건너뛰고 Alt+Tab일 때만 버튼에 포커스를 준다.
export function nextFocusKeyFor(browserName: string) {
  return browserName === "webkit" ? "Alt+Tab" : "Tab";
}
