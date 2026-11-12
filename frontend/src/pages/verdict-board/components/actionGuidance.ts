import type { PolledResource } from "@/shared/api/polledResource";
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

/**
 * 가장 최근 조회가 성공한 응답만 보여 준다.
 * 재조회가 실패하면 앞선 판정이 지금도 맞는지 알 수 없어 숨긴다.
 * 재조회가 진행 중일 때는 앞선 성공 응답을 그대로 둔다. 주기마다 문장이 사라졌다 나타나지 않게 한다.
 */
export function actionGuidanceOf(resource: PolledResource<unknown>): ActionGuidanceView {
  if (resource.status !== "success") {
    return HIDDEN_GUIDANCE;
  }
  return actionGuidanceFrom(resource.latestSuccess.body);
}
