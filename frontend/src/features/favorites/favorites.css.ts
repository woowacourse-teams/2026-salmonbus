import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const section = style({ margin: "0 0 24px" });
export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  margin: "24px 0 12px",
  fontSize: 15,
  fontWeight: 600,
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const card = style({
  display: "block",
  padding: "16px",
  border: "1px solid #E8E4DF",
  borderRadius: 12,
  background: vars.color.surface,
  color: vars.color.textStrong,
  textDecoration: "none",
});
export const subtle = style({ margin: "8px 0", color: vars.color.textSubtle, fontSize: 12, lineHeight: 1.6 });
export const error = style({ margin: "12px 0", color: "#BA3529", fontSize: 13, lineHeight: 1.6 });
export const action = style({
  padding: 6,
  border: 0,
  borderRadius: 4,
  background: "transparent",
  color: vars.color.textSubtle,
  font: "inherit",
  fontSize: 13,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
export const groupRow = style([
  card,
  { display: "flex", justifyContent: "space-between", alignItems: "center", minHeight: 66 },
]);
export const count = style({ display: "block", marginTop: 7, fontSize: 12, color: vars.color.brand });
export const add = style([
  card,
  {
    width: "100%",
    marginTop: 12,
    padding: 12,
    textAlign: "center",
    color: vars.color.brand,
    fontSize: 13,
    background: "transparent",
  },
]);
export const tabs = style({ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 });
export const tab = style({
  border: 0,
  borderRadius: 20,
  padding: "8px 14px",
  background: "#EEECE7",
  color: vars.color.textBody,
  font: "inherit",
  fontSize: 13,
  cursor: "pointer",
  selectors: { '&[aria-selected="true"]': { background: vars.color.brand, color: "white" } },
});
export const title = style({ display: "flex", alignItems: "center", gap: 12, margin: "0 0 28px", fontSize: 25 });
export const back = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 32,
  height: 32,
  borderRadius: "50%",
  background: vars.color.liveBus,
  color: "white",
  textDecoration: "none",
  fontSize: 20,
});
export const field = style({
  display: "block",
  width: "100%",
  padding: "14px",
  margin: "12px 0",
  minHeight: 48,
  border: "1px solid #E8E4DF",
  borderRadius: 12,
  background: "white",
  font: "inherit",
  fontSize: 16,
});
export const choice = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  padding: 14,
  marginTop: 10,
  border: "1px solid #E8E4DF",
  borderRadius: 10,
  fontSize: 14,
  selectors: { "&:has(input:checked)": { borderColor: vars.color.brand, background: "#FFF5F1" } },
});
globalStyle(`${choice} input`, { width: 18, height: 18, accentColor: vars.color.brand });
export const choiceCount = style({ marginLeft: "auto", fontSize: 12, color: vars.color.textSubtle });
export const star = style({
  display: "grid",
  placeItems: "center",
  width: 32,
  height: 32,
  padding: 0,
  border: 0,
  borderRadius: "50%",
  background: "transparent",
  cursor: "pointer",
});
export const homeEdit = style([
  action,
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
