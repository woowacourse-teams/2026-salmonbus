import { useEffect, useReducer, useState } from "react";
import { Link } from "react-router";
import type { FavoriteStatus, FavoriteTarget, HomeFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { SeatCountBadge } from "@/shared/components/SeatCountBadge";
import { toSeatLevel } from "@/shared/utils/seatGrade";
import { boardPathFor } from "@/shared/routing/paths";
import { useFavorites, useHomeFavorites } from "./FavoritesContext";
import { FavoritesNotice } from "./FavoritesNotice";
import { OrderDialog } from "./OrderDialog";
import { favoritePath } from "./favoritePolicy";
import bookmarkIcon from "./bookmark.svg";
import editIcon from "./home-edit.svg";
import * as styles from "./favorites.css";

const STATUS_LABEL: Record<Exclude<FavoriteStatus, "READY">, string> = {
  NO_VEHICLE: "도착 예정 차량이 없어요",
  PREPARING: "도착 예보를 준비하고 있어요",
  UNAVAILABLE: "현재 노선에서 이 정류장을 이용할 수 없어요",
  AMBIGUOUS: "승차할 정류장을 다시 확인해 주세요",
  ERROR: "이 즐겨찾기의 정보를 불러오지 못했어요",
};

export function HomeFavorites() {
  const { groups, resource, reload } = useFavorites();
  const [selected, setSelected] = useState<string | null>(null);
  const group = groups.find((candidate) => candidate.id === selected) ?? groups[0];
  const [ordering, setOrdering] = useState(false);
  const [revision, retry] = useReducer((value: number) => value + 1, 0);
  const summary = useHomeFavorites(group?.id ?? null, revision);
  const latest = latestSuccessOf(summary);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (latest === null) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [latest]);
  const favorites = latest?.body.groups.find((candidate) => candidate.id === group?.id)?.favorites ?? [];
  return (
    <section className="amp-mask" aria-label="내 즐겨찾기">
      <FavoritesNotice resource={resource} onRetry={reload} />
      {groups.length > 0 && (
        <div className={styles.tabs}>
          <div role="tablist" aria-label="즐겨찾기 그룹" className={styles.tabs} style={{ marginBottom: 0, flex: 1 }}>
            {groups.map((candidate, index) => (
              <button
                className={styles.tab}
                role="tab"
                id={`group-tab-${candidate.id}`}
                aria-controls="home-favorites"
                aria-selected={group?.id === candidate.id}
                tabIndex={group?.id === candidate.id ? 0 : -1}
                key={candidate.id}
                onClick={() => setSelected(candidate.id)}
                onKeyDown={(event) => {
                  const next =
                    event.key === "ArrowRight"
                      ? (index + 1) % groups.length
                      : event.key === "ArrowLeft"
                        ? (index + groups.length - 1) % groups.length
                        : event.key === "Home"
                          ? 0
                          : event.key === "End"
                            ? groups.length - 1
                            : null;
                  if (next === null) return;
                  event.preventDefault();
                  setSelected(groups[next]!.id);
                  event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
                }}
              >
                {candidate.name}
              </button>
            ))}
          </div>
          <button
            type="button"
            className={styles.homeEdit}
            aria-label="그룹과 즐겨찾기 순서 편집"
            onClick={() => setOrdering(true)}
          >
            <img src={editIcon} width="20" height="20" alt="" draggable={false} />
          </button>
        </div>
      )}
      {group && <FavoritesNotice resource={summary} onRetry={retry} />}
      <div
        id="home-favorites"
        role={group ? "tabpanel" : undefined}
        aria-labelledby={group ? `group-tab-${group.id}` : undefined}
      >
        {((resource.status === "success" && !group) || (latest !== null && favorites.length === 0)) && (
          <div className={styles.homeEmpty}>
            <img src={bookmarkIcon} width="34" height="34" alt="" draggable={false} />
            <p className={styles.homeEmptyTitle}>등록된 즐겨찾기가 없어요</p>
            <p className={styles.homeEmptyDescription}>자주 타는 노선에서 즐겨찾기를 추가해 보세요!</p>
          </div>
        )}
        <ul className={styles.list}>
          {favorites.map((favorite) => (
            <FavoriteCard
              key={favorite.id}
              favorite={favorite}
              savedDirection={group?.favorites.find((candidate) => candidate.id === favorite.id)?.direction}
              elapsed={Math.max(0, (now - (latest?.receivedAt ?? now)) / 1000)}
              onRetry={retry}
            />
          ))}
        </ul>
      </div>
      {ordering && <OrderDialog onClose={() => setOrdering(false)} />}
    </section>
  );
}

function FavoriteCard({
  favorite,
  savedDirection,
  elapsed,
  onRetry,
}: {
  favorite: HomeFavorite;
  savedDirection: FavoriteTarget["direction"] | undefined;
  elapsed: number;
  onRetry: () => void;
}) {
  const direction = favorite.stop.direction ?? savedDirection;
  const terminalStopName = favorite.route.terminalStopName?.trim();
  return (
    <li className={styles.card}>
      <Link
        className={styles.cardLink}
        to={
          direction
            ? favoritePath({ routeId: favorite.route.id, stopId: favorite.stop.stopId, direction })
            : boardPathFor(favorite.route.id)
        }
      >
        <div className={styles.cardHeading}>
          <span className={styles.route}>{favorite.route.displayName || "노선 정보 없음"}</span>
          <span style={{ flex: 1 }}>{favorite.stop.name || "정류장 정보 없음"}</span>
          <span className={styles.subtle} style={{ fontSize: 10, whiteSpace: "nowrap" }}>
            {terminalStopName
              ? `${terminalStopName} 방면`
              : direction === "UP"
                ? "상행"
                : direction === "DOWN"
                  ? "하행"
                  : "방면 정보 없음"}
          </span>
        </div>
        {favorite.status === "READY" && favorite.approachingVehicles.length > 0 ? (
          <ul className={styles.rows}>
            {favorite.approachingVehicles.map((vehicle, index) => {
              const remaining =
                vehicle.eta.status === "AVAILABLE" ? Math.max(0, vehicle.eta.remainingSeconds - elapsed) : null;
              const forecast = vehicle.forecast;
              const label =
                forecast.status !== "AVAILABLE" || forecast.expectedSeats === undefined
                  ? "좌석 정보 없음"
                  : forecast.expectedSeats < 1
                    ? "도착 시 남은 좌석 없어요"
                    : `도착 시 ${Math.floor(forecast.expectedSeats)}석 예상해요`;
              return (
                <li className={styles.arrival} key={vehicle.vehicleId ?? index}>
                  <span>
                    <strong>{vehicle.horizonStops}정류장 전</strong>
                    <span className={styles.subtle}>
                      {" "}
                      {remaining === null || remaining <= 0 ? "도착 정보 없음" : `${Math.ceil(remaining / 60)}분 후`}
                    </span>
                  </span>
                  <SeatCountBadge
                    tone={
                      forecast.status === "AVAILABLE" ? toSeatLevel(forecast.seatAvailableProbability) : "unavailable"
                    }
                    label={label}
                  />
                </li>
              );
            })}
          </ul>
        ) : (
          <p className={styles.subtle}>
            {favorite.status === "READY" ? STATUS_LABEL.NO_VEHICLE : STATUS_LABEL[favorite.status]}
          </p>
        )}
      </Link>
      {favorite.status === "ERROR" && (
        <button type="button" className={styles.action} onClick={onRetry}>
          다시 확인하기
        </button>
      )}
    </li>
  );
}
