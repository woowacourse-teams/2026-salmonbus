import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

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
