import { keyframes, style } from "@vanilla-extract/css";
import { PRESS_IN, PRESS_OUT, PRESSED_SCALE } from "@/shared/styles/press";
import { vars } from "@/shared/styles/tokens.css";

const revealAfterDelay = keyframes({
  from: { visibility: "hidden" },
});

export const status = style({
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
});

export const emptyNotice = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  marginTop: "120px",
  textAlign: "center",
});

export const emptyTitle = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: "16px",
  fontWeight: 700,
});

export const emptyDescription = style({
  margin: "8px 0 0",
  color: vars.color.textSubtle,
  fontSize: "13px",
});

export const notice = style({
  margin: "20px 4px 0",
  color: vars.color.textSubtle,
  fontSize: "14px",
  fontWeight: 600,
});

export const loadingNotice = style([notice, { animation: `${revealAfterDelay} 0s 200ms both` }]);

export const retryButton = style({
  minHeight: "44px",
  marginTop: "8px",
  padding: "0 18px",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: "22px",
  background: "transparent",
  color: vars.color.textStrong,
  fontWeight: 700,
});

// 누른 줄의 배경이 글자 양옆으로 12px 넓게 보이도록, 영역을 양옆으로 넓히고 줄 안쪽에 같은 여백을 둔다.
const ROW_INSET = "12px";

export const section = style({
  minHeight: 0,
  overflowY: "auto",
  margin: `16px -${ROW_INSET} 0`,
});

export const sectionTitle = style({
  margin: "0 0 4px",
  padding: `0 ${ROW_INSET}`,
  color: vars.color.textBody,
  fontSize: "13px",
  fontWeight: 600,
});

export const list = style({
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const item = style({
  display: "grid",
  gridTemplateColumns: "minmax(58px, max-content) 1fr",
  columnGap: "8px",
  alignItems: "center",
  width: "100%",
  minHeight: "50px",
  padding: `0 ${ROW_INSET}`,
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
