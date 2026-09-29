import { describe, expect, it } from "@jest/globals";
import { codePointLength, limitCodePoints, normalizedBody } from "./messageBodyPolicy";

describe("codePointLength", () => {
  it("이모지를 UTF-16 길이가 아니라 코드포인트 하나로 센다", () => {
    expect("🚌".length).toBe(2);
    expect(codePointLength("🚌")).toBe(1);
  });
});

describe("normalizedBody", () => {
  it("공백과 201자는 거절하고 200자는 받는다", () => {
    expect(normalizedBody("   ", 200)).toBeNull();
    expect(normalizedBody("가".repeat(200), 200)).toBe("가".repeat(200));
    expect(normalizedBody("가".repeat(201), 200)).toBeNull();
  });
});

describe("limitCodePoints", () => {
  it("입력을 UTF-16 길이가 아니라 코드포인트 수로 자른다", () => {
    expect(limitCodePoints("🚌🚌🚌", 2)).toBe("🚌🚌");
    expect(limitCodePoints("가나", 5)).toBe("가나");
  });
});
