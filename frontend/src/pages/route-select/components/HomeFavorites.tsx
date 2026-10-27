import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import { useEffect, useReducer, useRef, useState, type KeyboardEvent } from "react";
import { Link } from "react-router";
import type { FavoriteStatus, FavoriteTarget, HomeFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { SeatCountBadge } from "@/shared/components/SeatCountBadge";
import { toSeatEstimate, toSeatLevel } from "@/shared/utils/seatGrade";
import { seatLabelOf } from "@/shared/utils/seatLabelOf";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";
import { boardPathFor, favoritePath } from "@/shared/routing/paths";
import { useFavorites, useHomeFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { FavoriteEditor } from "@/shared/components/favorite-editor/FavoriteEditor";
import { Notice } from "@/shared/components/Notice";
import bookmarkIcon from "@/shared/assets/icons/bookmark.svg";
import editIcon from "@/shared/assets/icons/favorite-edit.svg";
import * as homeStyles from "./HomeFavorites.css";
import * as buttons from "@/shared/styles/button.css";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { visuallyHidden } from "@/shared/styles/accessibility.css";

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
  const [animateGroup, setAnimateGroup] = useState(true);
  const changeMode = (editing: boolean) => {
    setAnimateGroup(false);
    setOrdering(editing);
  };
  const [notice, showNotice] = useReducer((value: number) => value + 1, 0);
  const editButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    return ordering ? () => editButton.current?.focus() : undefined;
  }, [ordering]);
  const [revision, retry] = useReducer((value: number) => value + 1, 0);
  const summary = useHomeFavorites(group ? { kind: "group", groupId: group.id } : null, revision);
  const latest = latestSuccessOf(summary);
  const [previous, setPrevious] = useState({ groupId: group?.id, latest });
  const previousGroup = groups.find((candidate) => candidate.id === previous.groupId);
  const switching = summary.status === "pending" && previous.latest !== null && previousGroup !== undefined;
  const shownLatest = switching ? previous.latest : latest;
  const shownGroup = switching ? previousGroup : group;
  const selectGroup = (id: string) => {
    if (id === group?.id) return;
    if (latest !== null) setPrevious({ groupId: group?.id, latest });
    setAnimateGroup(true);
    setSelected(id);
  };
  function handleGroupTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
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
    const nextGroup = groups[next];
    if (nextGroup === undefined) return;
    event.preventDefault();
    selectGroup(nextGroup.id);
    event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (shownLatest === null) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [shownLatest]);
  const favorites = shownLatest?.body.groups.find((candidate) => candidate.id === shownGroup?.id)?.favorites ?? [];
  if (ordering)
    return (
      <div className={homeStyles.section}>
        <FavoriteEditor
          {...(group ? { initialGroupId: group.id } : {})}
          {...(latest ? { initialFavorites: favorites } : {})}
          onClose={(saved) => {
            changeMode(false);
            if (saved) showNotice();
          }}
        />
      </div>
    );
  const loadingError =
    resource.status === "loadingError" ? resource : group && summary.status === "loadingError" ? summary : null;
  if (loadingError) {
    return (
      <section className="amp-mask" aria-label="내 즐겨찾기">
        <FavoritesNotice home resource={loadingError} onRetry={resource.status === "loadingError" ? reload : retry} />
      </section>
    );
  }
  return (
    <section className={`${homeStyles.section} amp-mask`} aria-label="내 즐겨찾기">
      <FavoritesNotice resource={resource} onRetry={reload} />
      {groups.length > 0 && (
        <div className={homeStyles.tabs}>
          <div
            role="tablist"
            aria-label="즐겨찾기 그룹"
            className={homeStyles.tabs}
            style={{ marginBottom: 0, flex: 1 }}
          >
            {groups.map((candidate, index) => (
              <button
                className={homeStyles.tab}
                role="tab"
                id={`group-tab-${candidate.id}`}
                aria-controls="home-favorites"
                aria-selected={group?.id === candidate.id}
                tabIndex={group?.id === candidate.id ? 0 : -1}
                key={candidate.id}
                onClick={() => selectGroup(candidate.id)}
                onKeyDown={(event) => handleGroupTabKeyDown(event, index)}
              >
                {candidate.name}
              </button>
            ))}
          </div>
          <button
            ref={editButton}
            type="button"
            className={homeStyles.homeEdit}
            aria-label="그룹과 즐겨찾기 순서 편집"
            onClick={() => changeMode(true)}
          >
            <img src={editIcon} width="20" height="20" alt="" draggable={false} />
          </button>
        </div>
      )}
      {switching ? (
        <p role="status" className={visuallyHidden}>
          {group?.name} 그룹의 즐겨찾기를 불러오고 있어요…
        </p>
      ) : (
        group && <FavoritesNotice resource={summary} onRetry={retry} />
      )}
      <div
        id="home-favorites"
        role={group ? "tabpanel" : undefined}
        aria-labelledby={group ? `group-tab-${group.id}` : undefined}
        aria-busy={summary.status === "pending"}
        className={homeStyles.panel}
        data-loading={switching}
      >
        <div
          key={shownGroup?.id}
          className={shownLatest && animateGroup ? homeStyles.panelContent : undefined}
          inert={switching}
        >
          {((resource.status === "success" && !group) || (shownLatest !== null && favorites.length === 0)) && (
            <div className={homeStyles.homeEmpty}>
              <img src={bookmarkIcon} width="34" height="34" alt="" draggable={false} />
              <p className={homeStyles.homeEmptyTitle}>등록된 즐겨찾기가 없어요</p>
              <p className={homeStyles.homeEmptyDescription}>자주 타는 노선에서 즐겨찾기를 추가해 보세요!</p>
            </div>
          )}
          <ul className={homeStyles.list}>
            {favorites.map((favorite) => (
              <FavoriteCard
                key={favorite.id}
                favorite={favorite}
                savedDirection={
                  shownGroup?.favorites.find((candidate) => candidate.id === favorite.id)?.direction ?? null
                }
                elapsed={Math.max(0, (now - (shownLatest?.receivedAt ?? now)) / 1000)}
                onRetry={retry}
              />
            ))}
          </ul>
        </div>
      </div>
      {notice > 0 && <Notice key={notice} message="변경 사항을 저장했어요" />}
    </section>
  );
}

interface FavoriteCardProps {
  favorite: HomeFavorite;
  savedDirection: FavoriteTarget["direction"] | null;
  elapsed: number;
  onRetry: () => void;
}

function FavoriteCard({ favorite, savedDirection, elapsed, onRetry }: FavoriteCardProps) {
  const direction = favorite.stop.direction ?? savedDirection;
  return (
    <li className={homeStyles.card}>
      <Link
        className={homeStyles.link}
        to={
          direction
            ? favoritePath({ routeId: favorite.route.id, stopId: favorite.stop.stopId, direction })
            : boardPathFor(favorite.route.id)
        }
      >
        <div className={homeStyles.heading}>
          <RouteBadge>{favorite.route.displayName || "노선 정보 없음"}</RouteBadge>
          <span className={homeStyles.stop}>{favorite.stop.name || "정류장 정보 없음"}</span>
          <span className={homeStyles.direction}>{directionLabelOf(favorite.route.terminalStopName, direction)}</span>
        </div>
        <div className={homeStyles.forecast}>
          {favorite.status === "READY" && favorite.approachingVehicles.length > 0 ? (
            <ul className={homeStyles.rows}>
              {favorite.approachingVehicles.map((vehicle, index) => {
                const time = toRemainingTime(
                  vehicle.eta.status === "AVAILABLE" ? vehicle.eta.remainingSeconds - elapsed : null,
                );
                const forecast = vehicle.forecast;
                const arrived = vehicle.remainingStops === 0;
                const tone =
                  forecast.status === "AVAILABLE" ? toSeatLevel(forecast.seatAvailableProbability) : "unavailable";
                const estimate = toSeatEstimate(forecast.status === "AVAILABLE" ? forecast.expectedSeats : undefined);
                return (
                  <li className={homeStyles.arrival} key={vehicle.vehicleId ?? index}>
                    <span className={homeStyles.timing}>
                      {!arrived && (
                        <span className={time.kind === "known" ? homeStyles.eta : homeStyles.unavailableEta}>
                          {remainingTimeLabelOf(time)}
                        </span>
                      )}
                      <span className={arrived ? homeStyles.eta : homeStyles.distance}>
                        {stopsAwayLabelOf(vehicle.remainingStops)}
                      </span>
                    </span>
                    <SeatCountBadge tone={tone} className={homeStyles.seatBadge[tone]} label={seatLabelOf(estimate)} />
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className={homeStyles.notice}>
              {favorite.status === "READY" ? STATUS_LABEL.NO_VEHICLE : STATUS_LABEL[favorite.status]}
            </p>
          )}
        </div>
      </Link>
      {favorite.status === "ERROR" && (
        <button type="button" className={buttons.text} onClick={onRetry}>
          다시 확인하기
        </button>
      )}
    </li>
  );
}
