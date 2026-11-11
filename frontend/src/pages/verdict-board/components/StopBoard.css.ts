import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const board = style({
  flex: 1,
  minHeight: 0,
  overflow: "hidden auto",
  scrollbarWidth: "none",
  overscrollBehavior: "contain",
  touchAction: "pan-y",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: vars.radius.card,
  backgroundColor: vars.color.surface,
  selectors: {
    "&::-webkit-scrollbar": {
      display: "none",
    },
  },
});

const noticeLayout = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
});

export const noticeBoard = style([board, noticeLayout]);

export const sheetNoticeBoard = style([
  noticeLayout,
  {
    minHeight: 0,
    justifyContent: "safe center",
    overflowY: "auto",
    paddingBlock: 12,
  },
]);

export const notice = style({
  margin: 0,
  padding: "16px",
  color: vars.color.textSubtle,
  fontSize: vars.font.boardNotice,
  fontWeight: 500,
  textAlign: "center",
});

export const mascot = style({
  width: 96,
  height: 96,
  objectFit: "contain",
  flexShrink: 0,
});

export const errorNotice = style([notice, { padding: "0 16px", margin: "20px 0 16px" }]);

export const retryCaption = style({
  margin: "0 16px 16px",
  color: vars.color.textSubtle,
  fontSize: 13,
  textAlign: "center",
});

export const retry = style({
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 6,
  minHeight: 44,
  padding: "0 18px",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: 22,
  background: "transparent",
  color: vars.color.textStrong,
  font: "inherit",
  fontSize: 14,
  fontWeight: 700,
  cursor: "pointer",
  ":disabled": { cursor: "default", opacity: 0.6 },
  ":focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 4 },
});
