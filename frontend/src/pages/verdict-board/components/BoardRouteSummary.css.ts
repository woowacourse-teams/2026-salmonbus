import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const summary = style({ display: "flex", flexDirection: "column", gap: "10px" });
export const route = style({ display: "flex", alignItems: "center", gap: "10px", minWidth: 0 });

export const routeNumber = style({
  margin: 0,
  padding: "6px 10px",
  borderRadius: "9px",
  backgroundColor: vars.color.focusRing,
  color: vars.color.onInk,
  fontSize: "25px",
  lineHeight: 1.2,
  fontWeight: 900,
  letterSpacing: "-0.5px",
  whiteSpace: "nowrap",
  flexShrink: 0,
});

export const endpoints = style({
  minWidth: 0,
  margin: 0,
  color: vars.color.textBody,
  fontSize: "13px",
  fontWeight: 500,
  lineHeight: 1.5,
  overflowWrap: "anywhere",
});

export const details = style({
  display: "grid",
  gridTemplateColumns: "1.25fr 1fr 1fr",
  gap: "14px",
  margin: 0,
  padding: "6px 14px",
});

export const detail = style({
  position: "relative",
  display: "flex",
  flexDirection: "column",
  gap: "8px",
  minWidth: 0,
  selectors: {
    "& + &::before": {
      content: '""',
      position: "absolute",
      top: 0,
      left: "-7px",
      bottom: 0,
      width: "1px",
      backgroundColor: "#DBE3D8",
    },
  },
});

export const label = style({
  display: "flex",
  alignItems: "center",
  gap: "4px",
  margin: 0,
  color: vars.color.textSubtle,
  fontSize: "10px",
  lineHeight: 1.4,
});
export const summaryIcon = style({ flexShrink: 0, color: "#71816F" });
export const value = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: "13px",
  fontWeight: 600,
  lineHeight: 1.4,
  overflowWrap: "anywhere",
  fontVariantNumeric: "tabular-nums",
});
