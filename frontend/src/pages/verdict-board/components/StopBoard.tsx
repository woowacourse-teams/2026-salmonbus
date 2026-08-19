import type { StopView } from "../displayPolicy";
import { StopList } from "./StopList";
import * as styles from "./StopBoard.css";

type StopBoardProps = { status: "loading" } | { status: "error" } | { status: "ready"; stops: StopView[] };

export function StopBoard(props: StopBoardProps) {
  return <section className={styles.board}>{renderContent(props)}</section>;
}

function renderContent(props: StopBoardProps) {
  switch (props.status) {
    case "loading":
      return <p className={styles.message}>불러오는 중이에요</p>;
    case "error":
      return <p className={styles.message}>정보를 불러오지 못했어요</p>;
    case "ready":
      return <StopList stops={props.stops} />;
  }
}
