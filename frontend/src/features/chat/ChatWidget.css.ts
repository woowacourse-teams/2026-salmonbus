import { style } from "@vanilla-extract/css";
import { DESKTOP_MEDIA } from "./chatLayout";

const LAYER_MAX_WIDTH = "430px";
const DESKTOP_TOP = "128px";
const DESKTOP_BOTTOM = "32px";
const DESKTOP_LEFT = "calc(50% + 221px)";
const DESKTOP_WIDTH = "394px";

export const layer = style({
  position: "fixed",
  zIndex: 20,
  inset: 0,
  width: `min(100%, ${LAYER_MAX_WIDTH})`,
  margin: "0 auto",
  pointerEvents: "none",
  "@media": {
    [DESKTOP_MEDIA]: {
      top: `var(--board-list-top, ${DESKTOP_TOP})`,
      right: "auto",
      bottom: `var(--board-chat-layer-bottom, ${DESKTOP_BOTTOM})`,
      height: "var(--board-list-height, auto)",
      left: DESKTOP_LEFT,
      width: DESKTOP_WIDTH,
      margin: 0,
    },
  },
});
