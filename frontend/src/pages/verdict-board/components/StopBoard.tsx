import type { StopView } from "../displayPolicy";
import type { LiveVehicleView } from "../liveVehiclePolicy";
import { LOADING_NOTICE, LOAD_FAILED_NOTICE, OUT_OF_SERVICE_NOTICE } from "../verdictCopy";
import { StopList } from "./StopList";
import * as styles from "./StopBoard.css";

type NoticeStatus = "loading" | "error" | "outOfService";

type StopBoardProps =
  | { status: NoticeStatus }
  | {
      status: "ready";
      stops: StopView[];
      liveVehicleViews: LiveVehicleView[];
      liveMotionDurationMs: number;
      entering: boolean;
    };

const NOTICES: Record<NoticeStatus, string> = {
  loading: LOADING_NOTICE,
  error: LOAD_FAILED_NOTICE,
  outOfService: OUT_OF_SERVICE_NOTICE,
};

export function StopBoard(props: StopBoardProps) {
  if (props.status === "ready") {
    return (
      <section className={styles.board}>
        <StopList
          stops={props.stops}
          liveVehicleViews={props.liveVehicleViews}
          liveMotionDurationMs={props.liveMotionDurationMs}
          entering={props.entering}
        />
      </section>
    );
  }

  return (
    <section className={styles.noticeBoard}>
      <p className={styles.notice}>{NOTICES[props.status]}</p>
    </section>
  );
}
