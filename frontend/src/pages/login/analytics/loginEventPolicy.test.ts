import { describe, expect, it } from "@jest/globals";
import { favoritePath } from "@/shared/routing/paths";
import { localReturnTo } from "@/shared/routing/loginReturnPolicy";
import { loginEntryOf } from "./loginEventPolicy";

describe("loginEntryOf", () => {
  it("판정 보드의 즐겨찾기 안내에서 시작한 로그인은 favorite이다", () => {
    const returnTo = localReturnTo(favoritePath({ routeId: "204000057", direction: "UP", stopId: "203000123" }));

    expect(loginEntryOf(returnTo)).toBe("favorite");
  });

  it("마이페이지에서 시작한 로그인은 mypage다", () => {
    expect(loginEntryOf(localReturnTo("/mypage"))).toBe("mypage");
  });

  it("홈 카드와 돌아갈 곳 없이 연 로그인 화면은 home이다", () => {
    expect(loginEntryOf(localReturnTo("/"))).toBe("home");
    expect(loginEntryOf(localReturnTo(null))).toBe("home");
  });
});
