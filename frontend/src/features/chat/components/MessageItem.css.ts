import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const MESSAGE_MAX_WIDTH = "88%";
const OTHER_BUBBLE_RADIUS = "4px 16px 16px";
const OWN_BUBBLE_RADIUS = "16px 4px 16px 16px";
const RETRY_MIN_HEIGHT = "28px";
const RETRY_RADIUS = "7px";

const messageBase = style({
  display: "flex",
  maxWidth: MESSAGE_MAX_WIDTH,
  flexDirection: "column",
});

export const message = styleVariants({
  other: [messageBase, { alignItems: "flex-start", gap: "4px" }],
  own: [messageBase, { alignSelf: "flex-end", alignItems: "flex-end", gap: "3px" }],
});

export const nickname = style({ color: vars.color.textSubtle, fontSize: "11.5px", fontWeight: 600 });

export const bubbleRow = style({ display: "flex", alignItems: "flex-end", gap: "6px" });

const bubbleBase = style({
  maxWidth: "100%",
  margin: 0,
  padding: "8px 12px 9px",
  overflowWrap: "anywhere",
  whiteSpace: "pre-wrap",
  fontSize: "14px",
  fontWeight: 500,
  lineHeight: 1.45,
});

export const bubble = styleVariants({
  other: [
    bubbleBase,
    {
      border: `1px solid ${vars.color.forecastCardBorder}`,
      borderRadius: OTHER_BUBBLE_RADIUS,
      background: vars.color.background,
      color: vars.color.textBody,
    },
  ],
  own: [
    bubbleBase,
    {
      borderRadius: OWN_BUBBLE_RADIUS,
      background: vars.color.ink,
      color: vars.color.onInk,
    },
  ],
});

export const time = style({
  flexShrink: 0,
  paddingBottom: "2px",
  color: vars.color.textMuted,
  fontSize: "10.5px",
  fontWeight: 500,
  whiteSpace: "nowrap",
});

const deliveryBase = style({
  fontSize: "10.5px",
  whiteSpace: "nowrap",
});

export const delivery = styleVariants({
  sending: [deliveryBase, { color: vars.color.textMuted, fontWeight: 500 }],
  failed: [deliveryBase, { color: vars.color.liveBus, fontWeight: 600 }],
});

export const failureActions = style({
  display: "flex",
  alignItems: "center",
  gap: "8px",
  color: vars.color.liveBus,
  fontSize: "10.5px",
});

export const retryButton = style({
  minHeight: RETRY_MIN_HEIGHT,
  padding: "0 8px",
  border: 0,
  borderRadius: RETRY_RADIUS,
  background: vars.color.compactRowSurface,
  color: vars.color.textStrong,
  fontSize: "10.5px",
  fontWeight: 700,
  cursor: "pointer",
  selectors: {
    "&:disabled": { color: vars.color.faint, cursor: "default" },
  },
});
