import { useEffect, useState } from "react";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import { searchViewFor, type RoutesState } from "./displayPolicy";
import { recordSeenRoutes } from "./new-route/seenRouteStorage";

export function useRouteSearch(initialInput: string) {
  const [input, setInput] = useState(initialInput);
  const [gate] = useState(createLatestRequestGate);
  const [routesState, setRoutesState] = useState<RoutesState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const ticket = gate.issue();
    void fetchRoutes({ signal: ticket.signal }).then((result) => {
      if (!ticket.isLatest() || (!result.ok && result.failure.kind === "aborted")) {
        return;
      }
      if (!result.ok) {
        setRoutesState({ status: "error" });
        return;
      }
      const { routes } = result.body;
      const checkedAt = Date.now();
      setRoutesState({ status: "ready", routes, firstSeenTimes: recordSeenRoutes(routes, checkedAt), checkedAt });
    });
    return () => gate.close();
  }, [gate, attempt]);

  const changeInput = (value: string) => {
    const checkedAt = Date.now();
    setInput(value);
    setRoutesState((state) =>
      state.status === "ready"
        ? { status: "ready", routes: state.routes, firstSeenTimes: state.firstSeenTimes, checkedAt }
        : state,
    );
  };

  const retry = () => {
    setRoutesState({ status: "loading" });
    setAttempt((current) => current + 1);
  };

  return { input, view: searchViewFor(routesState, input), changeInput, retry };
}
