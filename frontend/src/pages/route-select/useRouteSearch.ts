import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import { searchViewFor, type RoutesState } from "./displayPolicy";

export function useRouteSearch() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [input, setInput] = useState(() => searchParams.get("q") ?? "");
  const [gate] = useState(createLatestRequestGate);
  const [routesState, setRoutesState] = useState<RoutesState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const ticket = gate.issue();
    void fetchRoutes({ signal: ticket.signal }).then((result) => {
      if (!ticket.isLatest() || (!result.ok && result.failure.kind === "aborted")) {
        return;
      }
      setRoutesState(result.ok ? { status: "ready", routes: result.body.routes } : { status: "error" });
    });
    return () => ticket.abort();
  }, [gate, attempt]);

  const changeInput = (value: string) => {
    setInput(value);
    setSearchParams(value === "" ? {} : { q: value }, { replace: true });
  };

  const retry = () => {
    setRoutesState({ status: "loading" });
    setAttempt((current) => current + 1);
  };

  return { input, view: searchViewFor(routesState, input), changeInput, retry };
}
