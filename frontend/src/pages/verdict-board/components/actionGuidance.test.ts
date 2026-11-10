import { describe, expect, it } from "@jest/globals";
import { actionGuidanceFrom } from "./actionGuidance";

const LINE = "이동과 대기를 비교할 근거가 부족해요.";

describe("actionGuidanceFrom", () => {
  it("shows the server sentence for a known decided code", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "ABSTAIN_EVIDENCE", guidance: LINE })).toEqual({
      status: "shown",
      line: LINE,
    });
  });

  it("hides a disabled payload", () => {
    expect(actionGuidanceFrom({ kind: "DISABLED" })).toEqual({ status: "hidden", reason: "NOT_AVAILABLE" });
  });

  it("rejects an unknown code, a blank line, and a broken payload", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO_NOW", guidance: LINE }).status).toBe("hidden");
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: "  " })).toEqual({
      status: "hidden",
      reason: "PAYLOAD_REJECTED",
    });
    expect(actionGuidanceFrom(null)).toEqual({ status: "hidden", reason: "PAYLOAD_REJECTED" });
  });

  it("rejects a line with a dash or middle dot and a seat forecast field", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: "이동\u2014대기" })).toEqual({
      status: "hidden",
      reason: "PAYLOAD_REJECTED",
    });
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: "이동\u00B7대기" })).toEqual({
      status: "hidden",
      reason: "PAYLOAD_REJECTED",
    });
    expect(
      actionGuidanceFrom({
        kind: "DECIDED",
        reasonCode: "GO",
        guidance: LINE,
        seatAvailableProbability: 0.4,
      }),
    ).toEqual({ status: "hidden", reason: "PAYLOAD_REJECTED" });
  });

  it("reads the sentence when an unrelated field is also present", () => {
    expect(actionGuidanceFrom({ kind: "DECIDED", reasonCode: "GO", guidance: LINE, note: "ignored" })).toEqual({
      status: "shown",
      line: LINE,
    });
  });
});
