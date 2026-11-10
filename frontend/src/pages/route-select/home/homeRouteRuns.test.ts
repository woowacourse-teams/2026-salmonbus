import { describe, expect, it } from "@jest/globals";
import type { Point } from "./homeMapData";
import { homeRouteRuns } from "./homeRouteRuns";

describe("homeRouteRuns", () => {
  it("역방향으로 겹치는 구간은 한 번만 그리며 갈라지는 길은 연결한다", () => {
    const a: Point = [127.1, 37.4];
    const b: Point = [127.2, 37.4];
    const c: Point = [127.3, 37.4];
    const d: Point = [127.2, 37.5];
    const e: Point = [127.1, 37.5];
    expect(
      homeRouteRuns([
        [a, b, c],
        [d, b, a, e],
      ]),
    ).toEqual([
      [a, b, c],
      [d, b],
      [a, e],
    ]);
  });
});
