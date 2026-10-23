import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { text } from "@/shared/styles/button.css";
import trashIcon from "@/shared/assets/icons/trash.svg";
import checkIcon from "../assets/group-check.svg";
import { modeTransition } from "@/shared/styles/transition.css";

export const section = style([
  modeTransition,
  {
    display: "flex",
    flexDirection: "column",
    gap: 12,
    maxWidth: 356,
    margin: "0 auto 24px",
    fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
    color: "#292A2D",
  },
]);
export const edit = style([
  text,
  { display: "grid", placeItems: "center", flexShrink: 0, width: 36, height: 32, padding: "6px 8px" },
]);
export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  margin: 0,
  fontSize: 17,
  fontWeight: 700,
  lineHeight: "25px",
  whiteSpace: "pre-wrap",
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const group = style({
  display: "flex",
  alignItems: "center",
  gap: 12,
  padding: 17,
  border: "1px solid #EAE6E0",
  borderRadius: 14,
  background: vars.color.surface,
  color: "inherit",
  textDecoration: "none",
});
export const info = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "flex-start",
  gap: 5,
  flex: 1,
  minWidth: 0,
});
export const name = style({ fontSize: 16, fontWeight: 600, lineHeight: "23px", overflowWrap: "anywhere" });
export const count = style({
  padding: "4px 9px",
  borderRadius: 7,
  background: "#FFF0EB",
  color: "#C95642",
  fontSize: 12,
  fontWeight: 600,
  lineHeight: "17px",
});
export const add = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 7,
  height: 50,
  border: "1px solid #DFD8D0",
  borderRadius: 14,
  color: vars.color.liveBus,
  fontSize: 14,
  fontWeight: 600,
  textDecoration: "none",
});

export const tools = style({ display: "flex", alignItems: "center", gap: 2 });
export const deleteTool = style({
  color: "#837B74",
  selectors: {
    "&::before": {
      content: "''",
      width: 20,
      height: 20,
      background: "currentColor",
      mask: `url(${trashIcon}) center / contain no-repeat`,
    },
  },
});
export const cancelSelection = style([text, { minHeight: 32, padding: 0, fontSize: 14, fontWeight: 500 }]);
export const representative = style({ flexShrink: 0, color: "#9A928B", fontSize: 11, fontWeight: 600 });
export const selection = style({
  cursor: "pointer",
  selectors: {
    "&:has(input:checked)": { borderColor: "#E8533F", background: "#FFF7F4" },
    "&:has(input:disabled)": { cursor: "default" },
  },
});
export const checkbox = style({
  appearance: "none",
  flexShrink: 0,
  width: 22,
  height: 22,
  margin: 0,
  border: "1px solid #D7D0C8",
  borderRadius: 6,
  background: "#FFFFFF",
  cursor: "inherit",
  selectors: {
    "&:checked": { borderColor: "#E8533F", background: `#E8533F url(${checkIcon}) center / 16px 16px no-repeat` },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 3 },
  },
});
export const deleteSelected = style({
  width: "100%",
  height: 50,
  padding: 0,
  border: 0,
  borderRadius: 14,
  background: "#E8533F",
  color: "white",
  font: "inherit",
  fontSize: 14,
  fontWeight: 700,
  cursor: "pointer",
  selectors: { "&:disabled": { background: "#E9E4DF", color: "#A79D93", cursor: "default" } },
});
export const dialog = style({
  selectors: {
    "&&": { width: "calc(100% - 64px)", maxWidth: 357, borderRadius: 20, padding: 24, background: "#FFFFFF" },
    "&&::backdrop": { background: "#201C1859" },
  },
});
export const dialogTitle = style({
  selectors: { "&&": { margin: 0, fontSize: 21, fontWeight: 700, lineHeight: 1.4, whiteSpace: "pre-line" } },
});
export const dialogActions = style({ display: "flex", gap: 10, marginTop: 16 });
export const confirm = style({
  flex: 1,
  minWidth: 0,
  height: 48,
  padding: 0,
  border: 0,
  borderRadius: 12,
  background: "#E8533F",
  color: "white",
  font: "inherit",
  fontSize: 15,
  fontWeight: 700,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
export const cancel = style([confirm, { background: "#F2EFEB", color: "#68615B" }]);
