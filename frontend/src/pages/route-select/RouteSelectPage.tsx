import { useEffect, useState } from "react";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { RouteList } from "./components/RouteList";
import salmongProud from "./assets/salmong-proud.png";
import { Pageinfo } from "./components/PageInfo";
import { titleMock, captionMock } from "./api/routeSelect.mock";

type RoutesState = { status: "loading" } | { status: "error" } | { status: "ready"; routes: RouteSummary[] };

export function RouteSelectPage() {
  const [gate] = useState(createLatestRequestGate);
  const [state, setState] = useState<RoutesState>({ status: "loading" });

  useEffect(() => {
    const ticket = gate.issue();
    void fetchRoutes({ signal: ticket.signal }).then((result) => {
      if (!ticket.isLatest() || (!result.ok && result.failure.kind === "aborted")) {
        return;
      }
      setState(result.ok ? { status: "ready", routes: result.body.routes } : { status: "error" });
    });
    return () => ticket.abort();
  }, [gate]);

  return (
    <>
      <header>
        <img src={salmongProud} alt="연어 버스 로고" />
        <Pageinfo title={titleMock} caption={captionMock} />
      </header>
      <main>{renderRoutes(state)}</main>
    </>
  );
}

function renderRoutes(state: RoutesState) {
  switch (state.status) {
    case "loading":
      return <p>노선을 불러오는 중이에요</p>;
    case "error":
      return <p>노선을 불러오지 못했어요</p>;
    case "ready":
      return <RouteList routes={state.routes} />;
  }
}
