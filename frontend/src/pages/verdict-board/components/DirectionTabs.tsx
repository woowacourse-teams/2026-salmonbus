import type { Direction } from "@/shared/api/routeForecast.types";
import type { DirectionView } from "../displayPolicy";
import * as styles from "./DirectionTabs.css";

interface DirectionTabsProps {
  directions: DirectionView[];
  selected: Direction;
  onSelect: (direction: Direction) => void;
}

export function DirectionTabs({ directions, selected, onSelect }: DirectionTabsProps) {
  return (
    <div className={styles.track} role="group" aria-label="운행 방향 선택">
      {directions.map((direction) => (
        <button
          key={direction.id}
          type="button"
          className={direction.id === selected ? styles.selectedTab : styles.tab}
          aria-pressed={direction.id === selected}
          onClick={() => onSelect(direction.id)}
        >
          {direction.label}
        </button>
      ))}
    </div>
  );
}
