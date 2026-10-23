import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const homeError = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 18,
  minHeight: 260,
  marginTop: 41,
  color: "#837B74",
  textAlign: "center",
});
export const homeErrorTitle = style({ margin: 0, fontSize: 16, fontWeight: 500, lineHeight: 1.5 });
export const homeErrorDescription = style({ margin: 0, fontSize: 13, lineHeight: 1.6 });
export const homeRetry = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 6,
  padding: "9px 14px",
  border: "1px solid #F0C2B8",
  borderRadius: 20,
  background: vars.color.surface,
  color: "#C34F3D",
  font: "inherit",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
});
