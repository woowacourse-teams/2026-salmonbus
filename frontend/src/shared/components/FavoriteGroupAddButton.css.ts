import { style } from "@vanilla-extract/css";
import { pill } from "@/shared/styles/selection.css";

export const add = style([
  pill,
  {
    display: "grid",
    placeItems: "center",
    width: 37,
    height: 37,
    padding: 0,
    flexShrink: 0,
    cursor: "pointer",
    selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
  },
]);
