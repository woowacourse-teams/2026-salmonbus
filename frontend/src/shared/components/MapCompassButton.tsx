import { compassDial, compassNeedle, compassNorth } from "./MapCompassButton.css";
import { mapButton } from "./mapControls.css";

const DIAL_CENTER = 16;
const NORTH_LABEL_RADIUS = 13.65;
const NEEDLE_OFFSET = 1.6;

interface MapCompassButtonProps {
  rotation: number;
  onReset: () => void;
}

export function MapCompassButton({ rotation, onReset }: MapCompassButtonProps) {
  const heading = -rotation;
  const angle = (heading * Math.PI) / 180;

  function resetFromKeyboard(button: HTMLButtonElement) {
    const location = button.nextElementSibling;
    const focusTarget = location instanceof HTMLButtonElement && !location.disabled ? location : button.parentElement;
    focusTarget?.focus({ preventScroll: true });
    onReset();
  }

  return (
    <button
      type="button"
      className={mapButton}
      aria-label="지도를 북쪽으로 돌리기"
      data-native-map-hit
      onKeyDown={(event) => {
        if (event.key === "Enter") event.preventDefault();
      }}
      onKeyUp={(event) => {
        if (event.key !== "Enter") return;
        event.preventDefault();
        resetFromKeyboard(event.currentTarget);
      }}
      onClick={(event) => {
        if (event.detail === 0) resetFromKeyboard(event.currentTarget);
        else onReset();
      }}
    >
      <span className={compassDial} aria-hidden="true">
        <span
          className={compassNeedle}
          data-testid="compass-needle"
          style={{ transform: `rotate(${heading}deg) translateY(${NEEDLE_OFFSET}px)` }}
        />
        <span
          className={compassNorth}
          style={{
            left: DIAL_CENTER + Math.sin(angle) * NORTH_LABEL_RADIUS,
            top: DIAL_CENTER - Math.cos(angle) * NORTH_LABEL_RADIUS,
          }}
        >
          N
        </span>
      </span>
    </button>
  );
}
