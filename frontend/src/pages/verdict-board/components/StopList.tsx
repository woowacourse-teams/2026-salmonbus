import { useState } from "react";
import type { StopView } from "../displayPolicy";
import { StopRow } from "./StopRow";
import type { RoutePosition } from "./TimelineMarker";
import * as styles from "./StopList.css";

interface StopListProps {
  stops: StopView[];
  entering: boolean;
}

// 회차 지점은 노선의 끝이 아니라 반대 방향으로 이어지는 지점이라 축을 끊지 않는다.
function routePositionFor(stop: StopView, index: number, lastIndex: number): RoutePosition {
  if (stop.isTurnaround) return "middle";
  if (lastIndex === 0) return "only";
  if (index === 0) return "start";
  if (index === lastIndex) return "end";
  return "middle";
}

export function StopList({ stops, entering }: StopListProps) {
  const [expandedSequence, setExpandedSequence] = useState<number | null>(null);
  const toggle = (sequence: number) => setExpandedSequence((current) => (current === sequence ? null : sequence));

  return (
    <ul className={entering ? styles.list.entering : styles.list.still}>
      {stops.map((stop, index) => (
        <StopRow
          key={stop.sequence}
          stop={stop}
          routePosition={routePositionFor(stop, index, stops.length - 1)}
          expanded={expandedSequence === stop.sequence}
          onToggle={() => toggle(stop.sequence)}
        />
      ))}
    </ul>
  );
}
