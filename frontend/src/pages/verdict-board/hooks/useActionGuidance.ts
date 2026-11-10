import { fetchSalmonAction } from "@/shared/api/salmonAction.api";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import type { StationArrivalSelection } from "@/shared/api/stationArrivals.types";
import { actionGuidanceFrom, HIDDEN_GUIDANCE, type ActionGuidanceView } from "../components/actionGuidance";

export function useActionGuidance(selection: StationArrivalSelection | null): ActionGuidanceView {
  const key =
    selection === null ? null : `${selection.routeId}/${selection.stationId}/${selection.stopSequence}`;
  const { resource } = usePolledRequest((_key, signal) => {
    if (selection === null) {
      return Promise.resolve({ ok: false as const, failure: { kind: "aborted" as const } });
    }
    return fetchSalmonAction(selection, signal);
  }, key);

  if (resource.status !== "success" && resource.status !== "refetchError") {
    return HIDDEN_GUIDANCE;
  }
  return actionGuidanceFrom(resource.latestSuccess.body);
}
