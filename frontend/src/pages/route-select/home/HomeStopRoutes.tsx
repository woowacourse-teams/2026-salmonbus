import { useEffect, useReducer } from "react";
import { RetryButton } from "@/shared/components/RetryButton";
import { Link } from "react-router";
import { latestSuccessOf, type LatestSuccess } from "@/shared/api/polledResource";
import { fetchRouteWatch } from "@/shared/api/routeForecast.api";
import type { RouteWatch } from "@/shared/api/routeForecast.types";
import type { ArrivalTargetVisit } from "@/shared/api/stationArrivals.types";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import { freshStationArrivalsOf, remainingArrivalSeconds } from "@/shared/api/stationArrivalView";
import { fetchStationDetail } from "@/shared/api/stationMap.api";
import { nextStationArrivalPollFrom, withStationArrivalClock } from "@/shared/api/stationArrivalsPolling";
import { VerdictChip } from "@/shared/components/VerdictChip";
import { useStationArrivals } from "@/shared/hooks/useStationArrivals";
import { favoritePath } from "@/shared/routing/paths";
import type { StationSheetEntry } from "@/shared/routing/boardEntry";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import { toSeatLevel, type SeatLevel } from "@/shared/utils/seatGrade";
import type { HomeStop, HomeStopRoute } from "./homeMapData";
import * as styles from "./HomeScreen.css";

interface NextBus {
  seconds: number | null;
  remainingStops: number | null;
  vehicleId: string | null;
  visit: ArrivalTargetVisit;
}

function badgeToneFor(routeTypeCode: number | null) {
  if (routeTypeCode === null) return "unknown";
  if ([11, 12, 14, 16, 17, 21, 22].includes(routeTypeCode)) return "seat";
  if (routeTypeCode === 13) return "city";
  if (routeTypeCode === 30) return "village";
  if ([51, 52, 53].includes(routeTypeCode)) return "airport";
  return "unknown";
}

function boardingLevel(
  latest: LatestSuccess<RouteWatch> | null,
  stopId: string,
  route: HomeStopRoute,
  next: NextBus | undefined,
): SeatLevel | null {
  if (!latest || !next?.vehicleId || !next.visit.stopSequence) return null;
  const { body, clock } = latest;
  if (
    body.route.id !== route.routeId ||
    body.route.referenceVersionId !== next.visit.referenceVersionId ||
    !(Date.parse(body.staleAt) > (clock?.now() ?? Date.now()))
  )
    return null;
  const stop = body.stops.find(
    (candidate) =>
      candidate.stopId === stopId &&
      candidate.direction === route.direction &&
      candidate.sequence === next.visit.stopSequence &&
      candidate.boardingAllowed,
  );
  const buses = stop?.approachingVehicles.filter((bus) => bus.vehicleId === next.vehicleId);
  const forecast = buses?.length === 1 ? buses[0]?.forecast : undefined;
  if (forecast?.status !== "AVAILABLE") return null;
  const probability = forecast.seatAvailableProbability;
  return probability >= 0 && probability <= 1 ? toSeatLevel(probability) : null;
}

interface HomeStopRoutesProps {
  stop: HomeStop;
  sheetSnap: StationSheetEntry;
}

export function HomeStopRoutes(props: HomeStopRoutesProps) {
  return props.stop.source === "station-api" ? <StationDetailRoutes {...props} /> : <LegacyStopRoutes {...props} />;
}

function LegacyStopRoutes({ stop, sheetSnap }: HomeStopRoutesProps) {
  const arrivals = useStationArrivals(stop.routes.length > 0 ? { stationId: stop.id } : null);
  const [, tick] = useReducer((value: number) => value + 1, 0);
  const fresh = freshStationArrivalsOf(arrivals, stop.id);
  const hasFreshArrival = fresh !== null;
  const latest = latestSuccessOf(arrivals);
  const waiting = arrivals.status === "pending" || (!fresh && latest?.body.refresh.status === "RUNNING");
  const nextBuses = new Map<string, NextBus>();
  if (fresh) {
    for (const { routeId, targetVisit, status, arrivals: buses } of fresh.body.routes) {
      if (status !== "READY" || targetVisit.matchStatus !== "MATCHED" || !targetVisit.direction) continue;
      const key = `${routeId}:${targetVisit.direction}`;
      for (const { eta, vehicleId, remainingStops } of buses) {
        const seconds = remainingArrivalSeconds(eta, fresh.now);
        const current = nextBuses.get(key);
        if (
          (seconds !== null && seconds < (current?.seconds ?? Infinity)) ||
          (seconds === null && remainingStops !== null && !current)
        ) {
          nextBuses.set(key, { seconds, remainingStops, vehicleId, visit: targetVisit });
        }
      }
    }
  }
  const groups = new Map<string, HomeStopRoute[]>();
  for (const route of stop.routes) {
    const group = groups.get(route.routeId);
    if (group) group.push(route);
    else groups.set(route.routeId, [route]);
  }
  useEffect(() => {
    if (!hasFreshArrival) return;
    const timer = window.setInterval(tick, 1_000);
    return () => window.clearInterval(timer);
  }, [hasFreshArrival]);

  return (
    <>
      {stop.routes.length > 0 ? (
        <ul className={styles.stopRouteList}>
          {[...groups].map(([routeId, routes]) => (
            <HomeStopRouteRows
              key={routeId}
              routeId={routeId}
              routes={routes}
              stopId={stop.id}
              nextBuses={nextBuses}
              waiting={waiting}
              sheetSnap={sheetSnap}
            />
          ))}
        </ul>
      ) : (
        <p className={styles.note}>정차 노선 정보를 확인하지 못했어요.</p>
      )}
    </>
  );
}

function HomeStopRouteRows({
  routeId,
  routes,
  stopId,
  nextBuses,
  waiting,
  sheetSnap,
}: {
  routeId: string;
  routes: HomeStopRoute[];
  stopId: string;
  nextBuses: Map<string, NextBus>;
  waiting: boolean;
  sheetSnap: StationSheetEntry;
}) {
  const needsForecast = routes.some((route) => nextBuses.get(`${routeId}:${route.direction}`)?.vehicleId);
  const { resource } = usePolledRequest(
    (id, signal) => fetchRouteWatch(id, { signal }),
    needsForecast ? routeId : null,
  );
  const latest = latestSuccessOf(resource);
  return routes.map((route) => {
    const key = `${routeId}:${route.direction}`;
    const next = nextBuses.get(key);
    const level = boardingLevel(latest, stopId, route, next);
    return (
      <StopRouteRow
        key={key}
        name={route.routeName}
        seconds={next?.seconds ?? null}
        waiting={waiting}
        remainingStops={next?.remainingStops ?? null}
        level={level}
        to={favoritePath({ ...route, stopId })}
        sheetSnap={sheetSnap}
      />
    );
  });
}

function StationDetailRoutes({ stop, sheetSnap }: HomeStopRoutesProps) {
  const [revision, retry] = useReducer((value: number) => value + 1, 0);
  const { resource, requestState } = usePolledRequest(
    async (id, signal) => withStationArrivalClock(await fetchStationDetail(id, signal)),
    stop.id,
    revision,
    nextStationArrivalPollFrom,
  );
  const latest = latestSuccessOf(resource);
  const body = latest?.body;
  const now = latest?.clock?.now() ?? Infinity;
  const hasFreshEta =
    body?.status === "READY" && body.reason !== "STALE" && !!body.snapshot && now < Date.parse(body.snapshot.staleAt);
  const [, tick] = useReducer((value: number) => value + 1, 0);
  useEffect(() => {
    if (!hasFreshEta) return;
    const timer = window.setInterval(tick, 1_000);
    return () => window.clearInterval(timer);
  }, [hasFreshEta]);
  const hasBoardData = resource.status === "success" || hasFreshEta;
  const waiting = resource.status === "pending" || body?.refresh.status === "RUNNING";
  const failed = resource.status === "loadingError" || resource.status === "refetchError";
  const retrying = requestState === "loading";
  const loadingRoutes = failed ? resource.status === "loadingError" && retrying : waiting;
  return (
    <>
      {failed && (
        <div className={styles.stopLoadError}>
          <p className={styles.note} role="status">
            {retrying ? "정류장 버스 정보를 확인하고 있어요…" : "정류장 버스 정보를 불러오지 못했어요."}
          </p>
          <RetryButton onRetry={retry} busy={retrying}>
            정류장 버스 정보 다시 불러오기
          </RetryButton>
        </div>
      )}
      {body?.routes.length ? (
        <ul className={styles.stopRouteList}>
          {body.routes.map((route, index) => {
            const key = `${route.routeId}:${route.direction}:${index}`;
            if (route.name === null) return <StopRouteSkeleton key={key} unavailable />;
            const next =
              route.arrivals.find(
                (vehicle) =>
                  (hasBoardData && vehicle.positionSource === "BOARD") ||
                  (hasFreshEta && vehicle.eta && remainingArrivalSeconds(vehicle.eta, now) !== null),
              ) ?? (hasFreshEta ? route.arrivals[0] : undefined);
            const seconds = hasFreshEta && next?.eta ? remainingArrivalSeconds(next.eta, now) : null;
            const probability = next?.probability ?? null;
            return (
              <StopRouteRow
                key={key}
                name={route.name}
                badgeTone={badgeToneFor(route.routeTypeCode)}
                seconds={seconds}
                waiting={waiting}
                remainingStops={next?.remainingStops ?? null}
                level={probability === null ? null : toSeatLevel(probability)}
                sheetSnap={sheetSnap}
                to={
                  route.direction
                    ? favoritePath({ routeId: route.routeId, stopId: stop.id, direction: route.direction })
                    : null
                }
              />
            );
          })}
        </ul>
      ) : loadingRoutes ? (
        <>
          {!failed && (
            <p className={visuallyHidden} role="status">
              정류장 버스 정보를 확인하고 있어요…
            </p>
          )}
          <ul className={styles.stopRouteList} aria-hidden="true">
            <StopRouteSkeleton />
            <StopRouteSkeleton />
          </ul>
        </>
      ) : (
        !failed && (
          <p className={styles.note} role="status">
            이 정류장의 정차 노선 정보가 없어요.
          </p>
        )
      )}
    </>
  );
}

function StopRouteSkeleton({ unavailable = false }: { unavailable?: boolean }) {
  return (
    <li data-testid="stop-route-skeleton">
      {unavailable && <span className={visuallyHidden}>노선 번호 정보 없음</span>}
      <div className={styles.stopRouteLink} aria-hidden="true">
        <span className={styles.stopRouteSummary}>
          <span className={styles.stopRouteNumberPlaceholder} />
          <span className={styles.stopRouteTimePlaceholder} />
        </span>
        <span className={styles.stopRouteVerdictPlaceholder} />
        <span className={styles.stopRouteArrowPlaceholder} />
      </div>
    </li>
  );
}

function StopRouteRow({
  name,
  badgeTone = "seat",
  seconds,
  waiting,
  remainingStops,
  level,
  to,
  sheetSnap,
}: {
  name: string;
  badgeTone?: ReturnType<typeof badgeToneFor>;
  seconds: number | null;
  waiting: boolean;
  remainingStops: number | null;
  level: SeatLevel | null;
  to: string | null;
  sheetSnap: StationSheetEntry;
}) {
  const eta =
    seconds === null
      ? waiting
        ? "확인 중"
        : "도착 정보 없음"
      : remainingTimeLabelOf(toRemainingTime(seconds), "곧도착");
  const contents = (
    <>
      <span className={styles.stopRouteSummary}>
        <span className={styles.stopRouteNumberSlot}>
          <span className={styles.stopRouteNumber} data-route-tone={badgeTone}>
            {name}
          </span>
        </span>
        <span className={styles.stopRouteTiming}>
          <span className={styles.stopRouteArrival} data-has-time={seconds !== null}>
            {eta}
          </span>
          {remainingStops !== null && (
            <span className={styles.stopRouteDistance}>{stopsAwayLabelOf(remainingStops)}</span>
          )}
        </span>
      </span>
      <span className={styles.stopRouteVerdict}>
        {level === null ? (
          <span className={styles.stopRouteArrival} aria-label="탑승 확률 정보 없음">
            —
          </span>
        ) : (
          <VerdictChip tone={level} />
        )}
      </span>
      {to && (
        <span className={styles.stopRouteArrow} aria-hidden="true">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none">
            <path
              d="M3 8h10m-4-4 4 4-4 4"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </span>
      )}
    </>
  );
  return (
    <li>
      {to ? (
        <Link className={styles.stopRouteLink} to={to} state={{ mapFlow: true, stationSheet: sheetSnap }}>
          {contents}
        </Link>
      ) : (
        <div className={styles.stopRouteLink}>{contents}</div>
      )}
    </li>
  );
}
