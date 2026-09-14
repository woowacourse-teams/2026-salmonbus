import { useEffect, useState } from "react";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { RouteList } from "./components/RouteList";
import salmongProud from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import salmonbusWordmark from "@/shared/assets/images/salmonbus-wordmark.webp";
import { PageInfo } from "@/shared/components/PageInfo";
import { titleMock, captionMock } from "./api/routeSelect.mock";
import * as styles from "./RouteSelectPage.css";

type RoutesState = { status: "loading" } | { status: "error" } | { status: "ready"; routes: RouteSummary[] };

export function RouteSelectPage() {
  const [gate] = useState(createLatestRequestGate);
  const [state, setState] = useState<RoutesState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  const retry = () => {
    setState({ status: "loading" });
    setAttempt((current) => current + 1);
  };

  useEffect(() => {
    const ticket = gate.issue();
    void fetchRoutes({ signal: ticket.signal }).then((result) => {
      if (!ticket.isLatest() || (!result.ok && result.failure.kind === "aborted")) {
        return;
      }
      setState(result.ok ? { status: "ready", routes: result.body.routes } : { status: "error" });
    });
    return () => ticket.abort();
  }, [gate, attempt]);

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.brandLockup}>
          <img className={styles.mascot} src={salmongProud} alt="연어 버스 로고" />
          <img className={styles.wordmark} src={salmonbusWordmark} width={272} height={70} alt="연어 버스 글자 로고" />
        </div>
        <div className={styles.intro}>
          <PageInfo title={titleMock} caption={captionMock} />
        </div>
      </header>
      <main className={styles.main}>{renderRoutes(state, retry)}</main>
    </div>
  );
}

function renderRoutes(state: RoutesState, onRetry: () => void) {
  switch (state.status) {
    case "loading":
      return <p>노선을 불러오는 중이에요</p>;
    case "error":
      return (
        <>
          <p>노선을 불러오지 못했어요</p>
          <button type="button" onClick={onRetry}>
            다시 시도하기
          </button>
        </>
      );
    case "ready":
      return <RouteList routes={state.routes} />;
  }
}
