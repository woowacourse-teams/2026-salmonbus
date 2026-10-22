import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const navigation = style({
  position: "fixed",
  bottom: 0,
  left: "50%",
  transform: "translateX(-50%)",
  display: "flex",
  width: "100%",
  maxWidth: 425,
  zIndex: 20,
  paddingBottom: "env(safe-area-inset-bottom, 0px)",
  borderTop: "1px solid #EEEAE6",
  background: "white",
});

export const tab = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 4,
  minHeight: 66,
  color: "#858079",
  fontSize: 10,
  textDecoration: "none",
  selectors: { '&[aria-current="page"]': { color: vars.color.brand } },
});
