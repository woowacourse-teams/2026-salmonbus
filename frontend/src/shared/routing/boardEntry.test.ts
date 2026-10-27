import { describe, expect, it } from "@jest/globals";
import { boardEntryFrom, boardEntryStateFor } from "./boardEntry";

describe("boardEntryFrom", () => {
  it.each(["search", "favorite"] as const)("앱 화면이 넘긴 진입 경로를 읽는다: %s", (entry) => {
    expect(boardEntryFrom(boardEntryStateFor(entry))).toBe(entry);
  });

  it.each<unknown>([null, undefined, { notice: "안내" }, { boardEntry: "push" }, { boardEntry: 1 }])(
    "넘긴 진입 경로가 없거나 알 수 없으면 direct다: %p",
    (state) => {
      expect(boardEntryFrom(state)).toBe("direct");
    },
  );
});
