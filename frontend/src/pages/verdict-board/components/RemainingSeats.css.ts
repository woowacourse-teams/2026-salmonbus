import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const badge = style({
  position: "relative",
  display: "block",
  flex: "none",
  boxSizing: "border-box",
  width: "32px",
  height: "32px",
  border: `2px solid ${vars.color.remainingSeat.border}`,
  borderRadius: vars.radius.remainingSeat,
  backgroundColor: vars.color.remainingSeat.surface,
  boxShadow: "none",
  color: vars.color.remainingSeat.value,
  fontFamily: '"Noto Sans KR", sans-serif',
  lineHeight: 1.1,
  textAlign: "center",
  whiteSpace: "nowrap",
});

export const label = style({
  position: "absolute",
  top: "3.5px",
  left: 0,
  width: "100%",
  color: vars.color.remainingSeat.label,
  fontSize: vars.font.remainingSeatLabel,
  fontWeight: 600,
  lineHeight: 1.1,
});

export const value = style({
  position: "absolute",
  top: "14px",
  left: 0,
  width: "100%",
  color: vars.color.remainingSeat.value,
  fontSize: vars.font.remainingSeatValue,
  fontVariantNumeric: "tabular-nums",
  fontWeight: 900,
  lineHeight: 1.1,
});
