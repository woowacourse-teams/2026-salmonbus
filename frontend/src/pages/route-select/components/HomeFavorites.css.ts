import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { card as cardBase, outlinedCard } from "@/shared/styles/surface.css";
import { pill } from "@/shared/styles/selection.css";
import { primary, text } from "@/shared/styles/button.css";
import { modeTransition } from "@/shared/styles/transition.css";

export const section = style([modeTransition, { width: "100%", maxWidth: 356, margin: "0 auto" }]);
export const browseSection = style([section, { display: "flex", flexDirection: "column", flex: 1 }]);
export const panel = style({
  display: "flex",
  flexDirection: "column",
  flex: 1,
});
export const panelInner = style({ display: "flex", flexDirection: "column", flex: 1 });
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
export const heading = style({
  display: "grid",
  gridTemplateColumns: "minmax(64px, max-content) minmax(0, 1fr)",
  alignItems: "center",
  gap: 10,
});
export const routeBadge = style({ alignSelf: "stretch" });
export const stopNameAndDirection = style({
  display: "flex",
  flexDirection: "column",
  gap: 3,
  minWidth: 0,
  overflowWrap: "anywhere",
});
export const stop = style({
  overflow: "hidden",
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
  color: "#292A2D",
  fontSize: 16,
  fontWeight: 700,
  lineHeight: 1.4,
  letterSpacing: "-0.5px",
});
export const direction = style({
  color: "#837B74",
  fontSize: 12,
  lineHeight: 1.4,
  wordBreak: "keep-all",
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
  fontSize: vars.font.arrivalTime,
  fontWeight: 600,
  lineHeight: "17px",
  whiteSpace: "nowrap",
});
export const distance = style({
  color: vars.color.textSubtle,
  fontSize: vars.font.distance,
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
export const unavailable = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 8,
  padding: "12px 0",
});

export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
const placeholder = style({
  display: "block",
  background: vars.color.mutedChipSurface,
  borderRadius: vars.radius.badge,
});
export const placeholderRoute = style([placeholder, { height: 42, borderRadius: 8 }]);
export const placeholderStop = style([placeholder, { width: "65%", height: 16 }]);
export const placeholderDirection = style([placeholder, { width: "40%", height: 12 }]);
export const placeholderTime = style([placeholder, { width: "24%", height: 14, marginInlineStart: 6 }]);
export const placeholderSeat = style([
  placeholder,
  { width: 148, maxWidth: "55%", height: 22, marginInlineStart: "auto", borderRadius: 999 },
]);
export const tabs = style({ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 });
export const groupTabs = style({
  display: "flex",
  alignItems: "center",
  gap: 8,
  flex: "0 1 auto",
  minWidth: 0,
  overflowX: "auto",
  padding: "19px 4px 4px",
  margin: "-19px -4px -4px",
});
export const tab = style([
  pill,
  {
    position: "relative",
    flexShrink: 0,
    cursor: "pointer",
    transition: "background-color 160ms ease, color 160ms ease",
    selectors: { '&[aria-selected="true"]': { background: vars.color.liveBus, color: "white" } },
    "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
  },
]);
export const groupMascot = style({
  position: "absolute",
  insetBlockStart: -15,
  insetInlineStart: 4,
  width: 24,
  height: 24,
  objectFit: "contain",
  rotate: "-8deg",
  pointerEvents: "none",
});
export const homeEdit = style([
  text,
  {
    display: "grid",
    placeItems: "center",
    flexShrink: 0,
    width: 37,
    height: 37,
    padding: 0,
    marginInlineStart: "auto",
  },
]);
export const homeEmpty = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 12,
  marginBlock: "auto",
  padding: "16px 0",
  color: "#837B74",
  textAlign: "center",
});
export const homeEmptyTitle = style({ margin: 0, fontSize: 16, fontWeight: 500 });
export const addFavoriteFooter = style({ display: "flex", justifyContent: "center", marginTop: 18 });
export const addFavorite = style([
  primary,
  {
    width: "auto",
    borderRadius: 10,
    background: vars.color.surface,
    color: vars.color.textBody,
    boxShadow: "inset 0 0 0 1px #E3DCD5, 0 2px 3px #3B2D2408",
    selectors: {
      [`${addFavoriteFooter} &`]: {
        minHeight: 36,
        padding: "5px 14px",
        borderRadius: 999,
        background: "#F5F4F2",
        fontSize: 12,
        boxShadow: "none",
      },
      "&:hover": { background: "#FFFCFA", boxShadow: "inset 0 0 0 1px #D7BFB5, 0 2px 3px #3B2D2408" },
      "&:active": { background: "#F4F0EC", boxShadow: "inset 0 0 0 1px #D7BFB5" },
    },
  },
]);
