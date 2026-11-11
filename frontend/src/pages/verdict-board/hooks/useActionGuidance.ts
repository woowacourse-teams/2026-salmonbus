import { useState } from "react";
import { fetchSalmonAction } from "@/shared/api/salmonAction.api";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import type { StationArrivalSelection } from "@/shared/api/stationArrivals.types";
import { actionGuidanceOf, type ActionGuidanceView } from "../components/actionGuidance";

/**
 * 선택한 정류장의 연어행동 안내를 주기적으로 조회한다.
 * 서버가 기능이 꺼져 있다고 답하면 이 화면에서는 다시 묻지 않는다.
 */
export function useActionGuidance(selection: StationArrivalSelection | null): ActionGuidanceView {
  const [disabled, setDisabled] = useState(false);
  const key =
    disabled || selection === null ? null : `${selection.routeId}/${selection.stationId}/${selection.stopSequence}`;
  const { resource } = usePolledRequest(async (_key, signal) => {
    if (selection === null) {
      return { ok: false as const, failure: { kind: "aborted" as const } };
    }
    const result = await fetchSalmonAction(selection, signal);
    if (result.ok && result.body.kind === "DISABLED") {
      setDisabled(true);
    }
    return result;
  }, key);

  return actionGuidanceOf(resource);
}
