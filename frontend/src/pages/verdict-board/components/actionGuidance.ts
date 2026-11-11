import { isRecord } from "@/shared/utils/valueGuards";

export type ActionGuidanceView =
  { status: "hidden"; reason: "NOT_AVAILABLE" | "PAYLOAD_REJECTED" } | { status: "shown"; line: string };

export const HIDDEN_GUIDANCE: ActionGuidanceView = { status: "hidden", reason: "NOT_AVAILABLE" };

/**
 * 서버가 문장을 실은 판정만 보여 준다.
 * 판정하지 못한 상태는 서버가 문장을 싣지 않는다. 그때는 꺼진 조회와 같이 숨긴다.
 */
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
  if (payload.guidance === undefined) {
    return HIDDEN_GUIDANCE;
  }
  if (typeof payload.guidance !== "string" || payload.guidance.trim() === "") {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  if ("seatAvailableProbability" in payload) {
    return { status: "hidden", reason: "PAYLOAD_REJECTED" };
  }
  return { status: "shown", line: payload.guidance };
}
