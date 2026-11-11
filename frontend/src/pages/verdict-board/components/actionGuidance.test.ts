import { describe, expect, it } from "@jest/globals";
import { actionGuidanceFrom } from "./actionGuidance";

const LINE = "지금은 정류장을 옮기지 않아도 돼요.";

describe("actionGuidanceFrom", () => {
  it("shows the server sentence of a decided payload", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "NO_MOVE_POLICY", guidance: LINE })).toEqual({
      status: "shown",
      line: LINE,
    });
  });

  it("shows the sentence of a reason code this screen has never seen", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "ADDED_LATER", guidance: LINE })).toEqual({
      status: "shown",
      line: LINE,
    });
  });

  it("hides a disabled payload and a decision without a sentence", () => {
    expect(actionGuidanceFrom({ kind: "DISABLED" })).toEqual({ status: "hidden", reason: "NOT_AVAILABLE" });
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "ABSTAIN_EVIDENCE" })).toEqual({
      status: "hidden",
      reason: "NOT_AVAILABLE",
    });
  });

  it("rejects a blank line, a non-text line, an unknown kind, and a broken payload", () => {
    const rejected = { status: "hidden", reason: "PAYLOAD_REJECTED" };

    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: "  " })).toEqual(rejected);
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: 3 })).toEqual(rejected);
    expect(actionGuidanceFrom({ kind: "MAYBE", guidance: LINE })).toEqual(rejected);
    expect(actionGuidanceFrom(null)).toEqual(rejected);
  });

  it("rejects a payload that carries a seat forecast field", () => {
    expect(
      actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: LINE, seatAvailableProbability: 0.4 }),
    ).toEqual({ status: "hidden", reason: "PAYLOAD_REJECTED" });
  });
});
