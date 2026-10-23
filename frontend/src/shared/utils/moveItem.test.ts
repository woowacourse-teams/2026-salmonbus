import { describe, expect, it } from "@jest/globals";
import { moveItem } from "./moveItem";

describe("배열 항목 이동", () => {
  it("순서를 바꾸어도 전체 ID를 보존하고 원본은 변경하지 않는다", () => {
    const ids = ["a", "b", "c"];
    expect(moveItem(ids, 0, 2)).toEqual(["b", "c", "a"]);
    expect(moveItem(ids, 0, -1)).toBe(ids);
    expect(moveItem(ids, 3, 0)).toBe(ids);
    expect(ids).toEqual(["a", "b", "c"]);
  });
});
