import { keyframes, style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { MOTION_MEDIA } from "../chatLayout";

const BANNER_MIN_HEIGHT = "36px";
const ICON_SIZE = "14px";
const BUTTON_MIN_HEIGHT = "28px";
const BUTTON_RADIUS = "7px";
const SPIN_DURATION = "900ms";

export const banner = style({
  display: "flex",
  flexShrink: 0,
  minHeight: BANNER_MIN_HEIGHT,
  alignItems: "center",
  gap: "8px",
  padding: "4px 12px 4px 18px",
  background: vars.color.compactRowSurface,
  color: vars.color.textMuted,
  fontSize: "12px",
  fontWeight: 600,
});

const spin = keyframes({ to: { transform: "rotate(360deg)" } });

const iconBase = style({
  width: ICON_SIZE,
  height: ICON_SIZE,
  flexShrink: 0,
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});

export const icon = styleVariants({
  progress: [
    iconBase,
    {
      "@media": {
        [MOTION_MEDIA]: { animation: `${spin} ${SPIN_DURATION} linear infinite` },
      },
    },
  ],
  success: [iconBase],
  error: [iconBase, { color: vars.color.focusRing }],
});

export const reconnectButton = style({
  minHeight: BUTTON_MIN_HEIGHT,
  marginLeft: "auto",
  padding: "0 10px",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: BUTTON_RADIUS,
  background: vars.color.surface,
  color: vars.color.textStrong,
  fontSize: "12px",
  fontWeight: 600,
  cursor: "pointer",
});
