import { useRef } from "react";
import type { ExpandableStopView, StopView } from "./displayPolicy";
import type { LiveVehicleView } from "./liveVehiclePolicy";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import type { StopSelection } from "./stopSelection";
import { LOADING_NOTICE, LOAD_FAILED_NOTICE, OUT_OF_SERVICE_NOTICE } from "./verdictCopy";
import { StopList } from "./StopList";
import type { StationArrivalDetails } from "./stationArrivalPolicy";
import * as styles from "./StopBoard.css";
import type { FavoriteBoardRoute } from "./FavoriteButton";
import salmongCrying from "@/shared/assets/images/salmong-logo/salmong-crying.png";
import refreshIcon from "@/shared/assets/icons/refresh.svg";

type NoticeStatus = "loading" | "error" | "outOfService";

type StopBoardProps =
  | { status: "loading" | "outOfService"; onRetry?: never; retrying?: never; autoRetry?: never }
  | { status: "error"; onRetry?: () => void; retrying?: boolean; autoRetry?: boolean }
  | {
      status: "ready";
      favoriteRoute?: FavoriteBoardRoute;
      stops: StopView[];
      liveVehicleViews: LiveVehicleView[];
      motion: LiveVehicleMotionStore;
      entering: boolean;
      selectedSequence: number | null;
      scrollRequest: StopSelection | null;
      selectedVehicleKey: string | null;
      stationArrivals: StationArrivalDetails | null;
      onToggle: (sequence: number) => void;
      onVehicleSelect: (key: string) => void;
      onVehicleDeselect: () => void;
      onStopExpand: (stop: ExpandableStopView, rowPosition: number) => void;
    };

const NOTICES: Record<NoticeStatus, string> = {
  loading: LOADING_NOTICE,
  error: LOAD_FAILED_NOTICE,
  outOfService: OUT_OF_SERVICE_NOTICE,
};

export function StopBoard(props: StopBoardProps) {
  const scrollContainerRef = useRef<HTMLElement | null>(null);
  if (props.status === "ready") {
    return (
      <section
        ref={scrollContainerRef}
        className={styles.board}
        role="region"
        aria-label="판정보드 정류장 목록"
        onClick={(event) => {
          if (event.target instanceof Element && event.target.closest("button, a") === null) props.onVehicleDeselect();
        }}
      >
        <StopList
          stops={props.stops}
          liveVehicleViews={props.liveVehicleViews}
          motion={props.motion}
          entering={props.entering}
          selectedSequence={props.selectedSequence}
          scrollRequest={props.scrollRequest}
          selectedVehicleKey={props.selectedVehicleKey}
          stationArrivals={props.stationArrivals}
          onToggle={props.onToggle}
          onVehicleSelect={props.onVehicleSelect}
          scrollContainerRef={scrollContainerRef}
          onStopExpand={props.onStopExpand}
          {...(props.favoriteRoute ? { favoriteRoute: props.favoriteRoute } : {})}
        />
      </section>
    );
  }

  return (
    <section className={styles.noticeBoard} role="region" aria-label="판정보드 정류장 목록">
      {props.status === "error" && (
        <img className={styles.mascot} src={salmongCrying} alt="연어 버스 로고" width={96} height={96} />
      )}
      <p
        className={props.status === "error" ? styles.errorNotice : styles.notice}
        role={props.status === "error" ? "alert" : "status"}
      >
        {NOTICES[props.status]}
      </p>
      {props.status === "error" && (
        <>
          {props.autoRetry && <p className={styles.retryCaption}>잠시 후 자동으로 다시 불러와요.</p>}
          {props.onRetry && (
            <button className={styles.retry} type="button" disabled={props.retrying} onClick={props.onRetry}>
              <img src={refreshIcon} width={14} height={14} alt="" />
              {props.retrying ? "불러오는 중…" : "다시 시도하기"}
            </button>
          )}
        </>
      )}
    </section>
  );
}
