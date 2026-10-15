import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const SAFE_AREA_TOP = "env(safe-area-inset-top, 0px)";
const SAFE_AREA_BOTTOM = "env(safe-area-inset-bottom, 0px)";

export const page = style({
  display: "flex",
  flexDirection: "column",
  height: ["100vh", "100dvh"],
  overflow: "hidden",
  backgroundColor: vars.color.background,
});

export const content = style({
  display: "flex",
  flex: 1,
  minHeight: 0,
  flexDirection: "column",
  gap: "13px",
  width: "100%",
  maxWidth: "430px",
  margin: "0 auto",
  padding: `calc(22px + ${SAFE_AREA_TOP}) 18px calc(32px + ${SAFE_AREA_BOTTOM})`,
});

// 헤더와 방향 탭은 스크롤 밖에 둔다. 목록만 카드 안에서 움직여야 탭과 카드 사이 여백이 남는다.
export const controls = style({
  display: "flex",
  flexDirection: "column",
  gap: "17px",
});
