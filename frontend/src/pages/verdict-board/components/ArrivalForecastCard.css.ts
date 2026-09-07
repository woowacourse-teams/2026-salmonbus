import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const ROW_HEIGHT = "44px";
const CARD_PADDING_X = "10px";

export const card = style({
  overflow: "hidden",
  width: "100%",
  maxWidth: "254px",
  boxShadow: `inset 0 0 0 1px ${vars.color.forecastCardBorder}`,
  borderRadius: vars.radius.forecastCard,
  backgroundColor: vars.color.forecastCardSurface,
});

export const list = style({
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const row = style({
  position: "relative",
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: "8px",
  height: ROW_HEIGHT,
  padding: `0 ${CARD_PADDING_X}`,
  selectors: {
    // 구분선은 카드 폭을 다 쓰지 않고 좌우 여백만큼 물러난다.
    "&:not(:first-child)::before": {
      content: '""',
      position: "absolute",
      top: 0,
      right: CARD_PADDING_X,
      left: CARD_PADDING_X,
      height: "1px",
      backgroundColor: vars.color.forecastCardBorder,
    },
  },
});

export const distance = style({
  flexShrink: 0,
  color: vars.color.textBody,
  fontSize: vars.font.distance,
  fontWeight: 500,
  whiteSpace: "nowrap",
});

export const notice = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  height: ROW_HEIGHT,
  margin: 0,
  padding: `0 ${CARD_PADDING_X}`,
  color: vars.color.textSubtle,
  fontSize: vars.font.cardNotice,
  fontWeight: 500,
  textAlign: "center",
});
