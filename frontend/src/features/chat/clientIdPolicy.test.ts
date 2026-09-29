import { describe, expect, it } from "@jest/globals";
import { createClientId } from "./clientIdPolicy";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function filledWith(value: number) {
  return {
    getRandomValues(array: Uint8Array<ArrayBuffer>) {
      array.fill(value);
      return array;
    },
  };
}

describe("createClientId", () => {
  it("randomUUID가 있으면 그 값을 그대로 쓴다", () => {
    const source = { ...filledWith(0), randomUUID: () => "0f8fad5b-d9cb-469f-a165-70867728950e" };

    expect(createClientId(source)).toBe("0f8fad5b-d9cb-469f-a165-70867728950e");
  });

  it("randomUUID가 없는 안전하지 않은 출처에서도 버전과 variant 비트를 맞춘 UUID v4를 만든다", () => {
    expect(createClientId(filledWith(0x00))).toBe("00000000-0000-4000-8000-000000000000");
    expect(createClientId(filledWith(0xff))).toBe("ffffffff-ffff-4fff-bfff-ffffffffffff");
  });

  it("randomUUID가 없을 때 만든 값은 매번 다른 UUID v4다", () => {
    const source = { getRandomValues: (array: Uint8Array<ArrayBuffer>) => crypto.getRandomValues(array) };

    const first = createClientId(source);
    const second = createClientId(source);

    expect(first).toMatch(UUID_V4);
    expect(second).toMatch(UUID_V4);
    expect(first).not.toBe(second);
  });
});
