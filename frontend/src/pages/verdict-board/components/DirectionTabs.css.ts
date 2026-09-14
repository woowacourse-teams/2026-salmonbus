import { keyframes, style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const TRACK_PADDING = "4px";
const TAB_GAP = "3px";
const TRAVEL = `calc(100% + ${TAB_GAP})`;
const PRESS = 0.97;
const TAB_LINE_HEIGHT = "20px";

export const track = style({
  position: "relative",
  display: "flex",
  gap: TAB_GAP,
  padding: TRACK_PADDING,
  overflow: "hidden",
  // 시안의 테두리는 안쪽에 그려진다. border 로 두면 트랙이 2px 커진다.
  boxShadow: `inset 0 0 0 1px ${vars.color.cardBorder}`,
  borderRadius: vars.radius.control,
  backgroundColor: vars.color.surface,
});

// 곡선 하나로 끝까지 미끄러진다. 중간에 키프레임을 두면 그 지점마다 감속이 걸려 끊긴다.
const slideToFirst = keyframes({ from: { translate: TRAVEL }, to: { translate: "0" } });
const slideToSecond = keyframes({ from: { translate: "0" }, to: { translate: TRAVEL } });

const indicatorBase = style({
  position: "absolute",
  top: TRACK_PADDING,
  bottom: TRACK_PADDING,
  left: TRACK_PADDING,
  width: `calc((100% - ${TRACK_PADDING} * 2 - ${TAB_GAP}) / 2)`,
  borderRadius: vars.radius.control,
  backgroundColor: vars.color.ink,
  willChange: "translate",
  animationDuration: vars.duration.spring,
  animationTimingFunction: vars.easing.slide,
});

const sliding = (animationName: string) => ({
  animationName,
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      animationName: "none",
    },
  },
});

const still = styleVariants({
  first: [indicatorBase, { translate: "0" }],
  second: [indicatorBase, { translate: TRAVEL }],
});

// 화면에 처음 그릴 때는 제자리에 있고, 방향을 바꾼 뒤부터 미끄러진다.
export const indicator = {
  still,
  moving: styleVariants({
    first: [still.first, sliding(slideToFirst)],
    second: [still.second, sliding(slideToSecond)],
  }),
};

const tabBase = style({
  position: "relative",
  flex: 1,
  minWidth: 0,
  overflow: "hidden",
  border: "none",
  borderRadius: vars.radius.control,
  padding: "8px 5px",
  backgroundColor: "transparent",
  fontFamily: "inherit",
  fontSize: vars.font.tab,
  fontWeight: 500,
  lineHeight: TAB_LINE_HEIGHT,
  letterSpacing: "-0.2px",
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
  transition: `color ${vars.duration.base} ease, scale ${vars.duration.fast} ease`,
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },
  selectors: {
    "&:focus-visible": {
      outline: `2px solid ${vars.color.focusRing}`,
      outlineOffset: "-2px",
    },
  },
});

export const tab = styleVariants({
  idle: [
    tabBase,
    {
      color: vars.color.textSubtle,
      cursor: "pointer",
      selectors: {
        "&:active": {
          scale: `${PRESS}`,
        },
      },
    },
  ],
  selected: [tabBase, { color: vars.color.onInk, cursor: "default" }],
});
