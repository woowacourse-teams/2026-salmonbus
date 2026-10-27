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
  gap: 0,
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

export const timing = style({
  display: "flex",
  flexDirection: "column",
  justifyContent: "center",
  flex: "0 1 76px",
  minWidth: "72px",
  paddingLeft: "6px",
  lineHeight: 1.4,
  whiteSpace: "nowrap",
});

export const distance = style({
  color: vars.color.textSubtle,
  fontSize: vars.font.distance,
  fontWeight: 500,
});

export const remainingTime = style({
  color: vars.color.textStrong,
  fontSize: vars.font.arrivalTime,
  fontWeight: 600,
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
