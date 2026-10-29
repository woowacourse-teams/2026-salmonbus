import { globalStyle, style } from "@vanilla-extract/css";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { PRESS_IN, PRESS_OUT, PRESSED_SCALE } from "@/shared/styles/press";
import { vars } from "@/shared/styles/tokens.css";

export const page = style({
  width: "100%",
  maxWidth: 425,
  "@media": { [PHONE_MEDIA]: { maxWidth: "none" } },
  minHeight: "100dvh",
  margin: "0 auto",
  paddingBottom: "calc(66px + env(safe-area-inset-bottom, 0px))",
  background: "#FAF9F7",
});

// 홈이 화면을 꽉 채울 때만 바깥 배경을 헤더와 같은 색으로 칠한다.
globalStyle(`html:has(${page})`, {
  "@media": { [PHONE_MEDIA]: { backgroundColor: vars.color.brand } },
});

export const header = style({
  padding: "calc(18px + env(safe-area-inset-top, 0px)) 24px 16px",
  background: vars.color.brand,
});
export const searchArea = style({ marginTop: 12 });
export const account = style({ padding: "24px 32px", textAlign: "left" });
export const welcome = style({ margin: "116px 0 0", color: "#68645F", fontSize: 14, lineHeight: 1.6 });

export const searchEntry = style({
  display: "flex",
  alignItems: "center",
  height: "48px",
  paddingLeft: "22px",
  borderRadius: "30px",
  backgroundColor: vars.color.surface,
  color: vars.color.textSubtle,
  fontSize: "16px",
  textDecoration: "none",
  // 평소에도 scale을 두어야 누르는 순간 stacking context가 새로 생기지 않는다.
  scale: "1",
  selectors: {
    "&:active": { scale: `${PRESSED_SCALE}` },
  },
  // 전환은 도착하는 상태의 값을 쓴다. 누를 때는 :active 쪽, 뗄 때는 기본 쪽 곡선이 돈다.
  "@media": {
    "(prefers-reduced-motion: no-preference)": {
      transition: PRESS_OUT,
      selectors: {
        "&:active": { transition: PRESS_IN },
      },
    },
  },
});

export const searchEntryText = style({
  flex: 1,
  minWidth: 0,
  height: "100%",
  padding: 0,
  border: "none",
  outline: "none",
  background: "transparent",
  // 16px보다 작으면 iOS가 포커스할 때 화면을 확대한다.
  fontSize: "16px",
  color: vars.color.textStrong,
  "::placeholder": { color: vars.color.textSubtle },
});

export const searchIcon = style({
  flexShrink: 0,
  width: "22px",
  height: "22px",
  marginRight: "19px",
  fill: "none",
  stroke: vars.color.brand,
  strokeWidth: 2,
  strokeLinecap: "round",
});
