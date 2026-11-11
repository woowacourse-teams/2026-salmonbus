import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { DirectionLabel } from "@/shared/components/DirectionLabel";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import { useEffect, useLayoutEffect, useReducer, useRef, useState, type KeyboardEvent } from "react";
import { Link, useOutletContext } from "react-router";
import type { FavoriteTarget, HomeFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { SeatCountBadge } from "@/shared/components/SeatCountBadge";
import { toSeatEstimate, toSeatLevel } from "@/shared/utils/seatGrade";
import { seatLabelOf } from "@/shared/utils/seatLabelOf";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";
import { boardEntryStateFor } from "@/shared/routing/boardEntry";
import { boardPathFor, favoritePath, paths } from "@/shared/routing/paths";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { playHaptic } from "@/shared/haptic/haptic";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { RetryButton } from "@/shared/components/RetryButton";
import { LoadError } from "@/shared/components/LoadError";
import { FAVORITES_LOAD_FAILURE_TITLE, MAX_FAVORITES_PER_GROUP } from "@/shared/utils/favoritePolicy";
import { FavoriteEditor } from "@/shared/components/favorite-editor/FavoriteEditor";
import { Notice } from "@/shared/components/Notice";
import starOutline from "@/shared/assets/icons/star-outline.svg";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import editIcon from "../../my-page/assets/pencil.svg";
import * as homeStyles from "./HomeFavorites.css";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { stopNameLabels } from "./stopNameLabels";
import { favoriteStatusLabelOf } from "./favoriteStatusLabelOf";
import { FavoriteGroupAddButton } from "@/shared/components/FavoriteGroupAddButton";
import type { FavoriteGroupContext } from "./RouteSelectScreen";

const EMPTY_FAVORITES: HomeFavorite[] = [];

export function HomeFavorites() {
  const { groups, resource, requestState, reload } = useFavorites();
  const {
    favoriteGroup: group,
    selectFavoriteGroup,
    summary: { resource: summary, requestState: summaryRequestState },
    retrySummary: retry,
    displayedGroup: shownGroup,
    displayedSummary: shownLatest,
    switchingGroup: switching,
  } = useOutletContext<FavoriteGroupContext>();
  const groupTabs = useRef<HTMLDivElement>(null);
  const [ordering, setOrdering] = useState(false);
  useLayoutEffect(() => {
    const tabs = groupTabs.current;
    const active = tabs?.querySelector('[aria-selected="true"]');
    if (!tabs || !active) return;
    const tab = active.getBoundingClientRect();
    const strip = tabs.getBoundingClientRect();
    tabs.scrollLeft += Math.min(0, tab.left - strip.left) + Math.max(0, tab.right - strip.right);
  }, [group?.id, ordering]);
  const list = useRef<HTMLUListElement>(null);
  const [nameLabels, setNameLabels] = useState<Record<string, string>>({});
  const [notice, showNotice] = useReducer((value: number) => value + 1, 0);
  const editButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    return ordering ? () => editButton.current?.focus() : undefined;
  }, [ordering]);
  const latest = latestSuccessOf(summary);
  const selectGroup = (id: string) => {
    if (id === group?.id) return;
    selectFavoriteGroup(id);
    playHaptic("selection");
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
  const canAddFavorite = (shownGroup?.favorites.length ?? 0) < MAX_FAVORITES_PER_GROUP;
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
            setOrdering(false);
            if (saved) showNotice();
          }}
        />
      </div>
    );
  if (resource.status === "loadingError") {
    return (
      <section {...maskedPropsFor()} aria-label="내 즐겨찾기">
        <FavoritesNotice resource={resource} requestState={requestState} onRetry={reload}>
          {({ retry, retrying }) => (
            <LoadError title={FAVORITES_LOAD_FAILURE_TITLE} description="잠시 후 다시 시도해 주세요.">
              <RetryButton onRetry={retry} busy={retrying}>
                {retrying ? "불러오는 중…" : "다시 시도하기"}
              </RetryButton>
            </LoadError>
          )}
        </FavoritesNotice>
      </section>
    );
  }
  const addFavoriteLink = (
    <Link
      className={homeStyles.addFavorite}
      to={paths.routeSearch}
      state={{ mapFlow: true }}
      onClick={(event) => {
        if (group && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
          selectFavoriteGroup(group.id);
        }
      }}
    >
      즐겨찾기 추가하기
    </Link>
  );
  return (
    <section {...maskedPropsFor(homeStyles.browseSection)} aria-label="내 즐겨찾기">
      <FavoritesNotice resource={resource} requestState={requestState} onRetry={reload} />
      {(groups.length > 0 || resource.status === "success") && (
        <div className={homeStyles.tabs}>
          <div ref={groupTabs} role="tablist" aria-label="즐겨찾기 그룹" className={homeStyles.groupTabs}>
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
                {group?.id === candidate.id && (
                  <img className={homeStyles.groupMascot} src={salmong} alt="" draggable={false} />
                )}
                {candidate.name}
              </button>
            ))}
          </div>
          <FavoriteGroupAddButton onCreated={(created) => selectGroup(created.id)} />
          {groups.length > 0 && (
            <button
              ref={editButton}
              type="button"
              className={homeStyles.homeEdit}
              aria-label="그룹과 즐겨찾기 순서 편집"
              onClick={() => setOrdering(true)}
            >
              <img src={editIcon} width="20" height="20" alt="" draggable={false} />
            </button>
          )}
        </div>
      )}
      {switching ? (
        <p role="status" className={homeStyles.groupLoading}>
          {group?.name} 그룹의 즐겨찾기를 불러오고 있어요…
        </p>
      ) : (
        group &&
        summary.status !== "loadingError" && (
          <FavoritesNotice resource={summary} requestState={summaryRequestState} onRetry={retry} />
        )
      )}
      <div
        id="home-favorites"
        role={group ? "tabpanel" : undefined}
        aria-labelledby={group ? `group-tab-${group.id}` : undefined}
        aria-busy={summary.status === "pending"}
        className={homeStyles.panel}
      >
        {summary.status === "loadingError" && (
          <FavoritesNotice resource={summary} requestState={summaryRequestState} onRetry={retry}>
            {({ retry, retrying }) => (
              <LoadError title={FAVORITES_LOAD_FAILURE_TITLE} description="잠시 후 다시 시도해 주세요.">
                <RetryButton onRetry={retry} busy={retrying}>
                  {retrying ? "불러오는 중…" : "다시 시도하기"}
                </RetryButton>
              </LoadError>
            )}
          </FavoritesNotice>
        )}
        <div key={shownGroup?.id} className={homeStyles.panelInner} inert={switching}>
          {((resource.status === "success" && !group) || (shownLatest !== null && favorites.length === 0)) && (
            <div className={homeStyles.homeEmpty}>
              <img src={starOutline} width="34" height="34" alt="" draggable={false} />
              <p className={homeStyles.homeEmptyTitle}>등록된 즐겨찾기가 없어요</p>
              {canAddFavorite && addFavoriteLink}
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
          {favorites.length > 0 && canAddFavorite && (
            <div className={homeStyles.addFavoriteFooter}>{addFavoriteLink}</div>
          )}
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
  if (favorite.status === "ERROR") {
    return (
      <li className={homeStyles.unavailable}>
        <p className={homeStyles.notice}>{favoriteStatusLabelOf("ERROR")}</p>
        <RetryButton onRetry={onRetry}>다시 시도하기</RetryButton>
      </li>
    );
  }
  const direction = favorite.stop.direction ?? savedDirection;
  if (!favorite.route.displayName || !favorite.stop.name) {
    return (
      <li className={homeStyles.card}>
        <div className={homeStyles.unavailable}>
          <p className={homeStyles.notice}>
            {favoriteStatusLabelOf(favorite.status === "READY" ? "ERROR" : favorite.status)}
          </p>
          {favorite.status !== "UNAVAILABLE" && direction && (
            <DirectionLabel
              className={homeStyles.direction}
              label={directionLabelOf(favorite.route.terminalStopName, direction)}
            />
          )}
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
        state={{ ...boardEntryStateFor("favorite"), mapFlow: true }}
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
            {favorite.status !== "UNAVAILABLE" && (
              <DirectionLabel
                className={homeStyles.direction}
                label={directionLabelOf(favorite.route.terminalStopName, direction)}
              />
            )}
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
              {favoriteStatusLabelOf(favorite.status === "READY" ? "NO_VEHICLE" : favorite.status)}
            </p>
          )}
        </div>
      </Link>
    </li>
  );
}
