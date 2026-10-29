import { globalStyle, style } from "@vanilla-extract/css";
import { POINTER_INPUT } from "@/shared/input-method/inputMethod";
import { vars } from "@/shared/styles/tokens.css";

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
globalStyle(`${POINTER_INPUT} ${choice} input:focus`, { outline: "none" });

export const addGroup = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 7,
  width: "100%",
  minHeight: 48,
  marginTop: 10,
  border: "1px solid #DFD8D0",
  borderRadius: 10,
  background: "transparent",
  color: vars.color.liveBus,
  font: "inherit",
  fontSize: 14,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
export const createGroup = style({ marginTop: 16, fontSize: 14 });
