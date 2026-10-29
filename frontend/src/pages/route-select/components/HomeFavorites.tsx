import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import { useEffect, useLayoutEffect, useReducer, useRef, useState, type KeyboardEvent } from "react";
import { Link } from "react-router";
import type { FavoriteStatus, FavoriteTarget, HomeFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { SeatCountBadge } from "@/shared/components/SeatCountBadge";
import { toSeatEstimate, toSeatLevel } from "@/shared/utils/seatGrade";
import { seatLabelOf } from "@/shared/utils/seatLabelOf";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";
import { boardEntryStateFor } from "@/shared/routing/boardEntry";
import { boardPathFor, favoritePath } from "@/shared/routing/paths";
import { useFavorites, useHomeFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice, FavoritesRetryButton } from "@/shared/components/FavoritesNotice";
import { FavoriteEditor } from "@/shared/components/favorite-editor/FavoriteEditor";
import { Notice } from "@/shared/components/Notice";
import starOutline from "@/shared/assets/icons/star-outline.svg";
import editIcon from "@/shared/assets/icons/favorite-edit.svg";
import * as homeStyles from "./HomeFavorites.css";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { stopNameLabels } from "./stopNameLabels";

const STATUS_LABEL: Record<Exclude<FavoriteStatus, "READY">, string> = {
  NO_VEHICLE: "도착 예정 차량이 없어요",
  PREPARING: "도착 예보를 준비하고 있어요",
  UNAVAILABLE: "현재 노선에서 이 정류장을 이용할 수 없어요",
  AMBIGUOUS: "승차할 정류장을 다시 확인해 주세요",
  ERROR: "정류장 정보를 불러오지 못했어요",
};
const EMPTY_FAVORITES: HomeFavorite[] = [];

export function HomeFavorites() {
  const { groups, resource, reload } = useFavorites();
  const [selected, setSelected] = useState<string | null>(null);
  const group = groups.find((candidate) => candidate.id === selected) ?? groups[0];
  const [ordering, setOrdering] = useState(false);
  const list = useRef<HTMLUListElement>(null);
  const [nameLabels, setNameLabels] = useState<Record<string, string>>({});
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
  const favorites =
    shownLatest?.body.groups.find((candidate) => candidate.id === shownGroup?.id)?.favorites ?? EMPTY_FAVORITES;
  useLayoutEffect(() => {
    const elements = Array.from(list.current?.querySelectorAll<HTMLElement>("[data-favorite-name]") ?? []);
    const firstElement = elements[0];
    if (!firstElement) return;
    const ruler = document.createElement("span");
    ruler.setAttribute("aria-hidden", "true");
    Object.assign(ruler.style, { position: "fixed", visibility: "hidden", left: "-10000px", whiteSpace: "nowrap" });
    document.body.appendChild(ruler);
    let active = true;
    const update = () => {
      if (!active) return;
      const css = getComputedStyle(firstElement);
      Object.assign(ruler.style, {
        fontFamily: css.fontFamily,
        fontSize: css.fontSize,
        fontWeight: css.fontWeight,
        fontStyle: css.fontStyle,
        letterSpacing: css.letterSpacing,
        fontKerning: css.fontKerning,
      });
      const inputs = elements.flatMap((element) => {
        const favorite = favorites.find((item) => item.id === element.dataset.favoriteName);
        if (!favorite?.stop.name) return [];
        const direction =
          favorite.stop.direction ?? shownGroup?.favorites.find((item) => item.id === favorite.id)?.direction;
        return [
          {
            id: favorite.id,
            name: favorite.stop.name,
            group: JSON.stringify([favorite.route.id, direction]),
            width: element.getBoundingClientRect().width,
          },
        ];
      });
      const next = stopNameLabels(inputs, (text) => {
        ruler.textContent = text;
        return ruler.getBoundingClientRect().width;
      });
      setNameLabels((current) => (JSON.stringify(current) === JSON.stringify(next) ? current : next));
    };
    const observer = new ResizeObserver(update);
    elements.forEach((element) => observer.observe(element));
    document.fonts.addEventListener("loadingdone", update);
    void document.fonts.ready.then(update);
    update();
    return () => {
      active = false;
      observer.disconnect();
      document.fonts.removeEventListener("loadingdone", update);
      ruler.remove();
    };
  }, [favorites, ordering, resource.status, summary.status, shownGroup]);
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
              <img src={starOutline} width="34" height="34" alt="" draggable={false} />
              <p className={homeStyles.homeEmptyTitle}>등록된 즐겨찾기가 없어요</p>
              <p className={homeStyles.homeEmptyDescription}>노선을 선택하고 자주 타는 정류장의 별을 눌러 보세요</p>
            </div>
          )}
          <ul ref={list} className={homeStyles.list}>
            {favorites.map((favorite) => (
              <FavoriteCard
                key={favorite.id}
                favorite={favorite}
                displayStopName={nameLabels[favorite.id] ?? favorite.stop.name ?? ""}
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
  displayStopName: string;
  savedDirection: FavoriteTarget["direction"] | null;
  elapsed: number;
  onRetry: () => void;
}

function FavoriteCard({ favorite, displayStopName, savedDirection, elapsed, onRetry }: FavoriteCardProps) {
  const direction = favorite.stop.direction ?? savedDirection;
  if (!favorite.route.displayName || !favorite.stop.name) {
    return (
      <li className={homeStyles.card}>
        <div className={homeStyles.unavailable}>
          <p className={homeStyles.notice}>
            {favorite.status === "READY" ? STATUS_LABEL.ERROR : STATUS_LABEL[favorite.status]}
          </p>
          {direction && (
            <span className={homeStyles.direction}>{directionLabelOf(favorite.route.terminalStopName, direction)}</span>
          )}
          {favorite.status === "ERROR" && <FavoritesRetryButton onRetry={onRetry}>다시 확인하기</FavoritesRetryButton>}
        </div>
      </li>
    );
  }
  return (
    <li className={homeStyles.card}>
      <Link
        className={homeStyles.link}
        to={
          direction
            ? favoritePath({ routeId: favorite.route.id, stopId: favorite.stop.stopId, direction })
            : boardPathFor(favorite.route.id)
        }
        state={boardEntryStateFor("favorite")}
      >
        <div className={homeStyles.heading}>
          <RouteBadge size="large" className={homeStyles.routeBadge}>
            {favorite.route.displayName}
          </RouteBadge>
          <div className={homeStyles.stopNameAndDirection}>
            <span className={homeStyles.stop} data-favorite-name={favorite.id}>
              {displayStopName !== favorite.stop.name ? (
                <>
                  <span aria-hidden="true">{displayStopName}</span>
                  <span className={visuallyHidden}>{favorite.stop.name}</span>
                </>
              ) : (
                favorite.stop.name
              )}
            </span>
            <span className={homeStyles.direction}>{directionLabelOf(favorite.route.terminalStopName, direction)}</span>
          </div>
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
                      {!arrived && time.kind === "known" && (
                        <span className={homeStyles.eta}>{remainingTimeLabelOf(time)}</span>
                      )}
                      <span className={arrived || time.kind === "unknown" ? homeStyles.eta : homeStyles.distance}>
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
    </li>
  );
}
