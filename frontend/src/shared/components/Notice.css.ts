import { style } from "@vanilla-extract/css";

export const toast = style({
  position: "fixed",
  bottom: "calc(82px + env(safe-area-inset-bottom, 0px))",
  left: "50%",
  transform: "translateX(-50%)",
  width: "calc(100% - 48px)",
  maxWidth: 377,
  zIndex: 30,
  padding: "14px 18px",
  borderRadius: 8,
  background: "#292A2D",
  color: "white",
  fontSize: 13,
  textAlign: "center",
  boxShadow: "0 4px 20px #0002",
});
