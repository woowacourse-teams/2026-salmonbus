import { keyframes, style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { card as cardBase, outlinedCard } from "@/shared/styles/surface.css";
import { pill } from "@/shared/styles/selection.css";
import { text } from "@/shared/styles/button.css";
import { modeTransition } from "@/shared/styles/transition.css";

export const section = style([modeTransition, { width: "100%", maxWidth: 356, margin: "0 auto" }]);
const enterGroup = keyframes({
  from: { opacity: 0, transform: "translateY(6px)" },
  to: { opacity: 1, transform: "translateY(0)" },
});
export const panel = style({
  transition: "opacity 160ms ease",
  selectors: { '&[data-loading="true"]': { opacity: 0.45 } },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
export const panelContent = style({
  animation: `${enterGroup} 220ms cubic-bezier(0.22, 1, 0.36, 1) both`,
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});
export const card = style([
  cardBase,
  outlinedCard,
  {
    padding: "16px 18px",
    fontFamily: "'Noto Sans KR', sans-serif",
  },
]);
export const link = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  color: "inherit",
  textDecoration: "none",
});
export const heading = style({ display: "flex", alignItems: "center", gap: 8, minWidth: 0, height: 23 });
export const stop = style({
  flex: 1,
  minWidth: 0,
  overflow: "hidden",
  color: "#292A2D",
  fontSize: 16,
  fontWeight: 700,
  lineHeight: "23px",
  letterSpacing: "-0.5px",
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
});
export const direction = style({
  flexShrink: 0,
  color: "#837B74",
  fontSize: 11,
  lineHeight: "16px",
  whiteSpace: "nowrap",
});
export const forecast = style({
  overflow: "hidden",
  padding: "0 10px",
  borderRadius: 10,
  background: vars.color.forecastCardSurface,
  boxShadow: `inset 0 0 0 1px ${vars.color.forecastCardBorder}`,
});
export const rows = style({ padding: 0, margin: 0, listStyle: "none" });
export const arrival = style({
  display: "flex",
  alignItems: "center",
  gap: 10,
  height: 44,
  selectors: { "&:not(:first-child)": { boxShadow: `inset 0 1px ${vars.color.forecastCardBorder}` } },
});
export const timing = style({
  display: "flex",
  flex: "1 1 0",
  flexWrap: "wrap",
  alignItems: "center",
  columnGap: 6,
  minWidth: 60,
  paddingLeft: 6,
});
export const eta = style({
  color: vars.color.textStrong,
  fontSize: 12,
  fontWeight: 600,
  lineHeight: "17px",
  whiteSpace: "nowrap",
});
export const distance = style({
  color: vars.color.textSubtle,
  fontSize: 10,
  fontWeight: 500,
  lineHeight: "14px",
  whiteSpace: "nowrap",
});
export const seatBadge = styleVariants(
  { ...vars.color.seat, low: { ...vars.color.seat.low, surface: "#F6EEDB", text: "#8C6A25" } },
  (tone) => ({
    selectors: {
      "&&": {
        width: 148,
        height: 22,
        padding: "0 6px",
        fontSize: 12,
        lineHeight: "22px",
        backgroundColor: tone.surface,
        color: tone.text,
      },
    },
  }),
);
export const notice = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  minHeight: 44,
  margin: 0,
  color: vars.color.textSubtle,
  fontSize: 12,
  textAlign: "center",
});

export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const tabs = style({ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 });
export const tab = style([
  pill,
  {
    cursor: "pointer",
    transition: "background-color 160ms ease, color 160ms ease",
    selectors: { '&[aria-selected="true"]': { background: vars.color.liveBus, color: "white" } },
    "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
  },
]);
export const homeEdit = style([
  text,
  { display: "grid", placeItems: "center", flexShrink: 0, width: 36, height: 32, padding: "6px 8px" },
]);
export const homeEmpty = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 18,
  minHeight: 260,
  color: "#837B74",
  textAlign: "center",
});
export const homeEmptyTitle = style({ margin: 0, fontSize: 16, fontWeight: 500 });
export const homeEmptyDescription = style({ margin: 0, fontSize: 13, lineHeight: 1.6 });
