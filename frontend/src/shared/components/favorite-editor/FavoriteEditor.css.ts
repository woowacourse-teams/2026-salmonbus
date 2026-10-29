import { globalStyle, style } from "@vanilla-extract/css";
import { POINTER_INPUT } from "@/shared/input-method/inputMethod";
import { vars } from "@/shared/styles/tokens.css";
import { primary, secondary } from "@/shared/styles/button.css";

export const editor = style({
  width: "100%",
  maxWidth: 356,
  margin: "0 auto",
  display: "flex",
  flexDirection: "column",
  gap: 16,
  fontFamily: "'Noto Sans KR', sans-serif",
});
export const groupName = style({
  minWidth: 0,
  padding: "9px 0",
  border: 0,
  background: "transparent",
  color: "#706B66",
  selectors: { '&[aria-pressed="true"]': { color: "white" } },
  font: "inherit",
  fontSize: 13,
  whiteSpace: "nowrap",
  overflow: "hidden",
  textOverflow: "ellipsis",
  cursor: "pointer",
});
export const info = style({ display: "flex", flexDirection: "column", gap: 6, flex: 1, minWidth: 0 });
export const loadingName = style({ height: 22, width: "80%", borderRadius: 4, background: "#EAE6E0" });
export const loadingDirection = style({ height: 18, width: "45%", borderRadius: 4, background: "#EAE6E0" });
export const cardHeading = style({ display: "flex", alignItems: "center", gap: 10, minWidth: 0 });
export const routeBadge = style({ alignSelf: "center", height: 42 });
export const stopNameAndDirection = style({
  display: "flex",
  flexDirection: "column",
  flex: 1,
  gap: 3,
  minWidth: 0,
  overflowWrap: "anywhere",
});
export const stop = style({
  color: "#292A2D",
  fontSize: 16,
  fontWeight: 700,
  lineHeight: 1.4,
  letterSpacing: "-0.5px",
});
export const direction = style({ color: "#837B74", fontSize: 12, lineHeight: 1.4, wordBreak: "keep-all" });
export const trash = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 36,
  height: 36,
  padding: 0,
  border: 0,
  borderRadius: 6,
  background: "transparent",
  cursor: "pointer",
});
export const done = style([
  primary,
  { minHeight: 46, borderRadius: 14, fontWeight: 700, padding: "12px 18px", background: vars.color.liveBus },
]);
export const deleteDialog = style({
  selectors: {
    "&&": {
      width: "calc(100% - 56px)",
      maxWidth: 369,
      borderRadius: 24,
      padding: 24,
      background: "white",
      fontFamily: "'Noto Sans KR', sans-serif",
    },
  },
});
export const deleteDescription = style({
  margin: 0,
  color: "#76706A",
  fontSize: 13,
  lineHeight: 1.65,
  textAlign: "center",
  overflowWrap: "anywhere",
});
export const deleteActions = style({ display: "flex", gap: 10, marginTop: 20 });
export const cancel = style([
  secondary,
  { minHeight: 50, borderRadius: 12, background: "#F0EFEC", color: "#706B66", fontWeight: 700 },
]);
export const confirm = style([
  primary,
  { minHeight: 50, borderRadius: 12, fontWeight: 700, background: vars.color.liveBus },
]);

export const deleteTitle = style({
  selectors: {
    "&&": {
      margin: "0 0 16px",
      fontSize: 21,
      fontWeight: 700,
      lineHeight: 1.4,
      letterSpacing: "-0.5px",
      textAlign: "center",
    },
  },
});
globalStyle(`${editor} button:focus-visible`, { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 3 });
globalStyle(`${POINTER_INPUT} ${editor} button:focus`, { outline: "none" });
