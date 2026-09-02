import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const listBase = style({
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const routeCanvas = style({
  position: "relative",
  minHeight: "100%",
});

export const liveVehicleOverlay = style({
  position: "absolute",
  zIndex: 2,
  inset: 0,
  overflow: "visible",
  pointerEvents: "none",
});

export const liveVehicle = style({
  position: "absolute",
  top: 0,
  left: vars.layout.axisX,
  width: "30px",
  height: "32px",
  transitionProperty: "transform",
  willChange: "transform",
});

const fadeIn = keyframes({
  from: { opacity: 0 },
  to: { opacity: 1 },
});

// 방향을 바꿀 때마다 목록이 다시 마운트되므로 키프레임 이름은 하나면 된다.
// 곡선은 알약과 다르다. 위치가 아니라 투명도라 앞이 급한 곡선이면 페이드가 아니라 깜빡임으로 보인다.
export const list = {
  still: listBase,
  entering: style([
    listBase,
    {
      animationName: fadeIn,
      animationDuration: vars.duration.spring,
      animationTimingFunction: "ease-out",
      "@media": {
        "(prefers-reduced-motion: reduce)": {
          animationName: "none",
        },
      },
    },
  ]),
};
