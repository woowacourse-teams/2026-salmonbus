import { describe, expect, it } from "@jest/globals";
import { resizeKeyframesFor } from "./peekMotion";

function widthsOf(keyframes: Keyframe[]) {
  return keyframes.map((keyframe) => Number.parseFloat(String(keyframe.width)));
}

describe("resizeKeyframesFor", () => {
  it("시작 폭에서 출발해 목표 폭에서 정확히 끝난다", () => {
    const widths = widthsOf(resizeKeyframesFor(72, 172));

    expect(widths[0]).toBe(72);
    expect(widths.at(-1)).toBeCloseTo(172, 6);
  });

  it("넓어질 때 목표보다 이동 거리의 약 10% 더 넓어졌다가 돌아온다", () => {
    const widths = widthsOf(resizeKeyframesFor(72, 172));

    expect(Math.max(...widths)).toBeGreaterThan(180);
    expect(Math.max(...widths)).toBeLessThan(183);
  });

  it("좁아질 때는 목표보다 좁아졌다가 돌아온다", () => {
    const widths = widthsOf(resizeKeyframesFor(172, 72));

    expect(Math.min(...widths)).toBeLessThan(64);
    expect(widths.at(-1)).toBeCloseTo(72, 6);
  });
});
