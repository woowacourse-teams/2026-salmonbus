import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const liveVehicleOverlay = style({
  position: "absolute",
  zIndex: 2,
  inset: 0,
  overflow: "visible",
  pointerEvents: "none",
});

export const liveVehicle = style({
  position: "absolute",
  top: 0,
  left: vars.layout.axisX,
  width: "30px",
  height: "32px",
  visibility: "hidden",
  willChange: "transform",
});
