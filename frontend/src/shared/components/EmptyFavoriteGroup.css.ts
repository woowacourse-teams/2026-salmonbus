import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const empty = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 8,
  padding: "36px 0",
  textAlign: "center",
});
export const emptyCount = style({ margin: 0, color: vars.color.liveBus, fontSize: 15, lineHeight: 1.6 });
export const emptyDescription = style({ margin: 0, color: "#837B74", fontSize: 14, lineHeight: 1.6 });
