import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const revealAfterDelay = keyframes({
  from: { visibility: "hidden" },
});

export const status = style({
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
});

export const notice = style({
  margin: "12px 4px 0",
  color: vars.color.onInk,
  fontSize: "14px",
  fontWeight: 600,
});

export const loadingNotice = style([notice, { animation: `${revealAfterDelay} 0s 200ms both` }]);

export const retryButton = style({
  minHeight: "44px",
  marginTop: "8px",
  padding: "0 18px",
  border: `1px solid ${vars.color.onInk}`,
  borderRadius: "22px",
  background: "transparent",
  color: vars.color.onInk,
  fontWeight: 700,
});

export const sheet = style({
  marginTop: "8px",
  padding: "10px 0",
  borderRadius: "16px",
  backgroundColor: vars.color.surface,
});

export const count = style({
  margin: "0 18px 4px",
  color: vars.color.textSubtle,
  fontSize: "12px",
});

export const list = style({
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const item = style({
  display: "grid",
  gridTemplateColumns: "1fr auto",
  alignItems: "center",
  gap: "8px",
  width: "100%",
  minHeight: "56px",
  padding: "8px 14px 8px 18px",
  border: "none",
  background: "transparent",
  textAlign: "left",
});

export const itemText = style({
  minWidth: 0,
});

export const routeNumber = style({
  display: "block",
  color: vars.color.textStrong,
  fontSize: "20px",
  fontWeight: 800,
});

export const stops = style({
  display: "block",
  overflow: "hidden",
  color: vars.color.textSubtle,
  fontSize: "12.5px",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});

export const chevron = style({
  width: "18px",
  height: "18px",
  fill: "none",
  stroke: vars.color.iconMuted,
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});
