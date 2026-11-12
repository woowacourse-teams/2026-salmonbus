import type { ComponentPropsWithoutRef } from "react";
import { mapButton } from "./mapControls.css";

type MapLocationButtonProps = Omit<ComponentPropsWithoutRef<"button">, "children" | "type" | "className">;

export function MapLocationButton(props: MapLocationButtonProps) {
  return (
    <button {...props} type="button" className={mapButton}>
      <svg
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="6" />
        <circle cx="12" cy="12" r="2" />
        <path d="M12 2v3m0 14v3M2 12h3m14 0h3" />
      </svg>
    </button>
  );
}
