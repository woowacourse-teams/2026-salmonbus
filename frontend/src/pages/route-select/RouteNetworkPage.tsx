import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { DirectionLabel, splitDirectionLabel } from "@/shared/components/DirectionLabel";
import { isInService, numberOrderedRoutesFor, type InServiceRoute } from "@/shared/route-list/routeListPolicy";
import { boardPathFor } from "@/shared/routing/paths";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import * as styles from "./RouteNetworkPage.css";

type RouteList = { status: "loading" | "error" } | { status: "ready"; routes: InServiceRoute[] };
const titleId = "service-routes-title";

export function RouteNetworkTitle() {
  return (
    <h2 id={titleId} className={styles.heading}>
      서비스 중 노선
    </h2>
  );
}

export function RouteNetworkPage() {
  const [list, setList] = useState<RouteList>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void fetchRoutes({ signal: controller.signal }).then((result) => {
      if (controller.signal.aborted) return;
      setList(
        result.ok
          ? { status: "ready", routes: numberOrderedRoutesFor(result.body.routes.filter(isInService)) }
          : { status: "error" },
      );
    });
    return () => controller.abort();
  }, [attempt]);

  return (
    <section className={styles.page}>
      {list.status === "ready" && list.routes.length > 0 ? (
        <ul className={styles.list} aria-labelledby={titleId}>
          {list.routes.map((route) => {
            const destination = splitDirectionLabel(route.endStopName);
            return (
              <li key={route.id}>
                <Link
                  className={styles.card}
                  to={boardPathFor(route.id)}
                  state={{ mapFlow: true, quietMapLoading: true }}
                >
                  <RouteBadge
                    className={`${styles.routeNumber} ${route.displayName.length > 4 ? styles.longRouteNumber : ""}`}
                  >
                    {route.displayName}
                  </RouteBadge>
                  <span className={styles.endpoints}>
                    <span className={styles.startEndpoint} title={route.startStopName}>
                      <DirectionLabel label={route.startStopName} />
                    </span>
                    <span className={styles.endEndpoint} title={route.endStopName}>
                      <span className={styles.endpointName}>{destination.name}</span>
                      {destination.details && <span className={styles.endpointDetails}>{destination.details}</span>}
                    </span>
                  </span>
                  <span className={visuallyHidden}>노선 보기</span>
                  <svg className={styles.open} viewBox="0 0 16 16" aria-hidden="true">
                    <path d="m6 4 4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                </Link>
              </li>
            );
          })}
        </ul>
      ) : (
        <div className={styles.notice}>
          {list.status === "loading" && (
            <div className={styles.list} aria-hidden="true">
              {[0, 1, 2].map((row) => (
                <div className={styles.skeleton} key={row}>
                  <span className={styles.placeholderNumber} />
                  <div className={styles.placeholderEndpoints}>
                    <span className={styles.placeholder} />
                    <span className={styles.placeholder} />
                  </div>
                </div>
              ))}
            </div>
          )}
          <p role="status" className={list.status === "loading" ? visuallyHidden : undefined}>
            {list.status === "loading"
              ? "노선을 불러오고 있어요…"
              : list.status === "error"
                ? "노선을 불러오지 못했어요."
                : "표시할 노선이 없어요."}
          </p>
          {list.status === "error" && (
            <button
              className={styles.retry}
              type="button"
              onClick={() => {
                setList({ status: "loading" });
                setAttempt((value) => value + 1);
              }}
            >
              다시 확인
            </button>
          )}
        </div>
      )}
    </section>
  );
}
