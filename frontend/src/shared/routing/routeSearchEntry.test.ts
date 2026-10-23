import { describe, expect, it } from "@jest/globals";
import { initialInputFrom, routeSearchStateFor } from "./routeSearchEntry";

describe("initialInputFrom", () => {
  it("홈이 넘긴 글자로 검색을 시작한다", () => {
    expect(initialInputFrom(routeSearchStateFor("3"))).toBe("3");
  });

  it.each<unknown>([null, undefined, "3", { notice: "안내" }, { input: 3 }])(
    "넘긴 글자가 없으면 빈 검색어로 시작한다: %p",
    (state) => {
      expect(initialInputFrom(state)).toBe("");
    },
  );
});
