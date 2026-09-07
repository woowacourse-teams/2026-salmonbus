import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const board = style({
  flex: 1,
  minHeight: 0,
  overflow: "hidden auto",
  scrollbarWidth: "none",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: vars.radius.card,
  backgroundColor: vars.color.surface,
  selectors: {
    "&::-webkit-scrollbar": {
      display: "none",
    },
  },
});

export const noticeBoard = style([
  board,
  {
    display: "flex",
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
  },
]);

export const notice = style({
  margin: 0,
  padding: "48px 16px",
  color: vars.color.textSubtle,
  fontSize: vars.font.boardNotice,
  fontWeight: 500,
  textAlign: "center",
});
