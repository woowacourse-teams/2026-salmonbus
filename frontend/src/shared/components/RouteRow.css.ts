import { style } from "@vanilla-extract/css";
import { PRESS_IN, PRESS_OUT, PRESSED_SCALE } from "@/shared/styles/press";
import { ROUTE_ROW_INSET } from "@/shared/styles/routeRow";
import { vars } from "@/shared/styles/tokens.css";

export const row = style({
  display: "grid",
  gridTemplateColumns: "minmax(58px, max-content) 1fr",
  columnGap: "8px",
  alignItems: "center",
  width: "100%",
  minHeight: "50px",
  padding: `0 ${ROUTE_ROW_INSET}`,
  border: "none",
  borderRadius: "12px",
  background: "transparent",
  textAlign: "left",
  // 동작 줄이기 설정에서도 눌린 줄을 알 수 있게 배경은 움직임과 따로 둔다.
  selectors: { "&:active": { backgroundColor: vars.color.pressedRowSurface } },
  "@media": {
    "(hover: hover)": {
      selectors: { "&:hover": { backgroundColor: vars.color.pressedRowSurface } },
    },
    // 전환은 도착하는 상태의 값을 쓴다. 누를 때는 :active 쪽, 뗄 때는 기본 쪽 곡선이 돈다.
    "(prefers-reduced-motion: no-preference)": {
      scale: "1",
      transition: PRESS_OUT,
      selectors: {
        "&:active": { scale: `${PRESSED_SCALE}`, transition: PRESS_IN },
      },
    },
  },
});

export const routeNumber = style({
  color: vars.color.textStrong,
  fontSize: "16px",
  fontWeight: 700,
});

export const stops = style({
  overflow: "hidden",
  color: vars.color.textBody,
  fontSize: "14px",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});

export const newBadge = style({
  gridColumn: 3,
  color: vars.color.brand,
  fontSize: "12px",
  fontWeight: 800,
});
