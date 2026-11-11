import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import stopBus from "./stop-bus.svg";

export const stopPinSize = 18;

export const stopPin = style({
  position: "relative",
  display: "grid",
  placeItems: "center",
  width: stopPinSize,
  height: stopPinSize,
  padding: 0,
  border: 0,
  borderRadius: 6,
  background: "transparent",
  cursor: "pointer",
  selectors: {
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});
export const stopPinGlyph = style({
  position: "relative",
  display: "grid",
  placeItems: "center",
  width: stopPinSize,
  height: stopPinSize,
  borderRadius: 6,
  border: "1px solid #DFB5A9",
  background: "#FFF9F5",
  color: "#984538",
  boxShadow: "0 1px 3px #71372E24",
  "::before": {
    content: '""',
    width: 14,
    height: 14,
    backgroundColor: "currentColor",
    mask: `url("${stopBus}") center / contain no-repeat`,
  },
  selectors: {
    [`${stopPin}[aria-pressed="true"] &`]: {
      borderColor: "#FFFFFF",
      background: "#B94033",
      color: vars.color.onBrand,
      boxShadow: "0 0 0 3px #F2AF9CC0, 0 2px 6px #71372E45",
    },
  },
});

export const stopPinLabel = style({
  display: "none",
  position: "absolute",
  top: `calc(50% + ${stopPinSize / 2 + 4}px)`,
  left: "50%",
  width: "max-content",
  transform: "translateX(-50%)",
  maxWidth: 160,
  overflow: "hidden",
  textOverflow: "ellipsis",
  padding: "4px 7px 2px",
  borderRadius: 6,
  border: "1px solid #EED5CC",
  background: vars.color.surface,
  boxShadow: "0 1px 6px #71372E26",
  color: vars.color.textStrong,
  fontSize: 11,
  fontWeight: 600,
  textAlign: "center",
  whiteSpace: "nowrap",
  pointerEvents: "none",
  selectors: {
    [`${stopPin}[data-label-visible="true"] &`]: { display: "block" },
    [`${stopPin}[aria-pressed="true"] &`]: {
      maxWidth: 220,
    },
  },
});
