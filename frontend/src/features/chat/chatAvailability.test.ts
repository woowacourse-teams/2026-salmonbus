import { describe, expect, it } from "@jest/globals";
import examples from "../../../../contract/examples/chat-v1.json";
import { availabilityFrom } from "./chatAvailability";

describe("availabilityFrom", () => {
  it("계약 예시의 availability를 그대로 받는다", () => {
    // given
    const availability = examples.availability;

    // when
    const parsed = availabilityFrom(availability);

    // then
    expect(parsed).toEqual(availability);
  });

  it("모르는 필드는 무시한다", () => {
    // given
    const withExtraField = { ...examples.availability, retentionHours: 24, audience: "all" };

    // when
    const parsed = availabilityFrom(withExtraField);

    // then
    expect(parsed).toEqual(examples.availability);
  });

  it("필수 필드가 빠지면 받지 않는다", () => {
    expect(availabilityFrom({ routeId: "204000057" })).toBeNull();
    expect(availabilityFrom({ ...examples.availability, maxBodyCodePoints: 0 })).toBeNull();
    expect(availabilityFrom("<!doctype html>")).toBeNull();
  });
});
