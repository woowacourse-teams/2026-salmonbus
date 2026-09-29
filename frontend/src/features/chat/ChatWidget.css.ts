import { style } from "@vanilla-extract/css";
import { DESKTOP_MEDIA } from "./chatLayout";

const LAYER_MAX_WIDTH = "430px";
const DESKTOP_TOP = "128px";
const DESKTOP_BOTTOM = "32px";
const DESKTOP_LEFT = "calc(50% + 239px)";
const DESKTOP_WIDTH = "360px";

export const layer = style({
  position: "fixed",
  zIndex: 20,
  inset: 0,
  width: `min(100%, ${LAYER_MAX_WIDTH})`,
  margin: "0 auto",
  pointerEvents: "none",
  "@media": {
    [DESKTOP_MEDIA]: {
      top: DESKTOP_TOP,
      right: "auto",
      bottom: DESKTOP_BOTTOM,
      left: DESKTOP_LEFT,
      width: DESKTOP_WIDTH,
      margin: 0,
    },
  },
});
