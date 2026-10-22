import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

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
export const choiceCount = style({ marginLeft: "auto", fontSize: 12, color: vars.color.textSubtle });
globalStyle(`${choice} input`, { width: 18, height: 18, accentColor: vars.color.brand });
