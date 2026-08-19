import { useState } from "react";
import { boardMock } from "./api/verdictBoard.mock";
import { directionViewsFor, stopViewsFor } from "./displayPolicy";
import type { Direction } from "./api/verdictBoard.types";
import { BoardHeader } from "./components/BoardHeader";
import { DirectionTabs } from "./components/DirectionTabs";
import { StopBoard } from "./components/StopBoard";
import * as styles from "./VerdictBoardPage.css";

export function VerdictBoardPage() {
  const [direction, setDirection] = useState<Direction>(boardMock.route.directions[0]?.id ?? "UP");

  return (
    <div className={styles.page}>
      <main className={styles.content}>
        <BoardHeader routeName={boardMock.route.displayName} />
        <DirectionTabs directions={directionViewsFor(boardMock)} selected={direction} onSelect={setDirection} />
        <StopBoard status="ready" stops={stopViewsFor(boardMock, direction)} />
      </main>
    </div>
  );
}
