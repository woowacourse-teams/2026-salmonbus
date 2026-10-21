import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const PRESS = 0.98;

// 감쇠 스프링(누를 때 stiffness 1000, damping 55 / 뗄 때 800, 55)을 1/60초 간격으로 뽑은 곡선이다.
// 배열 앞쪽은 linear()를 모르는 브라우저가 쓰는 값이다.
const PRESS_IN = [
  "scale 200ms cubic-bezier(0.35, 0.4, 0.15, 1)",
  "scale 200ms linear(0, 0.103 8.3%, 0.304 16.7%, 0.508 25%, 0.677 33.3%, 0.802 41.7%, 0.887 50%, 0.941 58.3%, 0.973 66.7%, 0.991 75%, 0.999 83.3%, 1.003 91.7%, 1)",
];
const PRESS_OUT = [
  "scale 350ms cubic-bezier(0.35, 0.8, 0.05, 1)",
  "scale 350ms linear(0, 0.083 4.8%, 0.247 9.5%, 0.42 14.3%, 0.572 19%, 0.694 23.8%, 0.786 28.6%, 0.854 33.3%, 0.902 38.1%, 0.935 42.9%, 0.957 47.6%, 0.973 52.4%, 0.983 57.1%, 0.989 61.9%, 0.993 66.7%, 0.996 71.4%, 0.997 76.2%, 0.998 81%, 0.999 85.7%, 0.999 90.5%, 1 95.2%, 1)",
];

export const form = style({
  display: "flex",
  alignItems: "center",
  height: "60px",
  paddingLeft: "22px",
  borderRadius: "30px",
  backgroundColor: vars.color.surface,
  // 평소에도 scale을 두어야 누르는 순간 stacking context가 새로 생기지 않는다.
  scale: "1",
  selectors: {
    "&:active": { scale: `${PRESS}` },
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

export const input = style({
  flex: 1,
  minWidth: 0,
  border: "none",
  outline: "none",
  background: "transparent",
  fontSize: "16px",
  color: vars.color.textStrong,
  "::placeholder": { color: vars.color.textSubtle },
  ":placeholder-shown": { caretColor: "transparent" },
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

export const clearButton = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: "44px",
  height: "44px",
  marginRight: "8px",
  border: "none",
  background: "transparent",
  color: vars.color.iconMuted,
});

export const clearIcon = style({
  width: "18px",
  height: "18px",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
});
