import type { Direction } from "@/shared/api/routeForecast.types";
import type { DirectionView } from "../displayPolicy";
import * as styles from "./DirectionTabs.css";

interface DirectionTabsProps {
  directions: DirectionView[];
  selected: Direction;
  animated: boolean;
  onSelect: (direction: Direction) => void;
}

export function DirectionTabs({ directions, selected, animated, onSelect }: DirectionTabsProps) {
  const selectedIndex = directions.findIndex((direction) => direction.id === selected);
  const position = selectedIndex === 1 ? "second" : "first";

  return (
    <div className={styles.track} role="group" aria-label="운행 방향 선택">
      {directions.length === 2 && (
        <span
          className={animated ? styles.indicator.moving[position] : styles.indicator.still[position]}
          aria-hidden="true"
        />
      )}
      {directions.map((direction) => (
        <button
          key={direction.id}
          type="button"
          className={direction.id === selected ? styles.tab.selected : styles.tab.idle}
          aria-pressed={direction.id === selected}
          onClick={() => onSelect(direction.id)}
        >
          {direction.label}
        </button>
      ))}
    </div>
  );
}
