import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const tracker = style({
  position: "relative",
  display: "block",
  flex: "none",
  width: "30px",
  height: "32px",
  padding: 0,
  border: 0,
  outline: "none",
  background: "transparent",
  pointerEvents: "auto",
  cursor: "pointer",
  transformOrigin: "50% 50%",
  transition: `transform ${vars.duration.fast} ${vars.easing.slide}`,
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
  },
  selectors: {
    '&[aria-pressed="true"]': { transform: "scale(1.12)" },
    "&:focus-visible": { transform: "scale(1.12)" },
  },
});

export const remainingSeats = style({
  position: "absolute",
  top: 0,
  right: "calc(100% + 4.5px)",
});

export const busIcon = style({
  position: "absolute",
  top: "2.5px",
  left: 0,
  display: "block",
  width: "30px",
  height: "27px",
  overflow: "hidden",
});

export const busOutline = style({
  fill: "none",
  stroke: vars.color.liveBus,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  strokeWidth: 1.974,
});

export const busBody = style({
  fill: vars.color.surface,
  stroke: vars.color.liveBus,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  strokeWidth: 1.974,
});

export const busDetail = style({
  fill: vars.color.liveBus,
});
