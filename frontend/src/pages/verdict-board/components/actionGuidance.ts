import { isRecord } from "@/shared/utils/valueGuards";

export const ACTION_REASON_CODES = [
  "GO",
  "NO_MOVE_POLICY",
  "NO_MOVE_NO_GAIN",
  "NO_MOVE_NO_CANDIDATE",
  "NO_MOVE_INFEASIBLE",
  "ABSTAIN_EVIDENCE",
  "ABSTAIN_UNCERTAIN_GAIN",
  "INVALID_INPUT",
  "WINDOW_EXPIRED",
] as const;

export type ActionReasonCode = (typeof ACTION_REASON_CODES)[number];

export type ActionGuidanceView =
  { status: "hidden"; reason: "NOT_AVAILABLE" | "PAYLOAD_REJECTED" } | { status: "shown"; line: string };

export const HIDDEN_GUIDANCE: ActionGuidanceView = { status: "hidden", reason: "NOT_AVAILABLE" };

const REASON_CODES: ReadonlySet<string> = new Set(ACTION_REASON_CODES);

export function actionGuidanceFrom(payload: unknown): ActionGuidanceView {
  if (!isRecord(payload)) {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if (payload.kind === "DISABLED") {
    return HIDDEN_GUIDANCE;
  }
  if (payload.kind !== "DECIDED") {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if (typeof payload.reasonCode !== "string" || !REASON_CODES.has(payload.reasonCode)) {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if (typeof payload.guidance !== "string" || payload.guidance.trim() === "") {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if (payload.guidance.includes("\u2014") || payload.guidance.includes("\u00B7")) {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if ("seatAvailableProbability" in payload) {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  return { status: "shown", line: payload.guidance };
}
